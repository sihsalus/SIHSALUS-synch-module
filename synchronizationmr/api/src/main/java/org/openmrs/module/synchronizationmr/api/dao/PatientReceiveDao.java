/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.api.dao;

import java.sql.*;
import org.hibernate.SessionFactory;
import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Comparte la transacción clínica: paciente, evento importado y confirmación se guardan juntos. */
@Repository("synchronizationmr.PatientReceiveDao")
public class PatientReceiveDao {
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessionFactory;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao localNodeDao;
	
	@javax.annotation.Resource(name = "synchronizationmr.PatientUpdateDao")
	private PatientUpdateDao updateDao;
	
	public long receiveUpdate(PatientUpdateEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new APIException("Patient reception requires a write transaction");
        }
        if (localNodeDao.getLocalServerId().equals(event.origin)) {
            throw new APIException("Cannot receive own-origin events");
        }
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            long confirmed = confirmed(connection, event.origin);
            if (event.sequence <= confirmed) {
                try (PreparedStatement q = connection.prepareStatement("select patient_uuid,event_uuid,payload_json from "
                        + PatientSyncDao.EVENTS + " where origin_server_id=? and entity_sequence=?")) {
                    q.setString(1, event.origin); q.setLong(2, event.sequence);
                    try (ResultSet rows = q.executeQuery()) {
                        if (rows.next() && event.patientUuid.equals(rows.getString(1)) && event.eventUuid.equals(rows.getString(2))
                            && event.sameContent(rows.getString(3)) && !rows.next()) { return confirmed; }
                    }
                }
                throw new APIException("Confirmed update does not match stored event");
            }
            if (confirmed == Long.MAX_VALUE || event.sequence != confirmed + 1) {
                throw new APIException("Patient event sequence is not consecutive");
            }
            updateDao.receive(connection, event);
            if (confirmed == 0) {
                try (PreparedStatement q = connection.prepareStatement("insert into synchronizationmr_patient_receipt (origin_server_id,confirmed_sequence) values (?,?)")) {
                    q.setString(1, event.origin); q.setLong(2, event.sequence); q.executeUpdate();
                }
            } else {
                try (PreparedStatement q = connection.prepareStatement("update synchronizationmr_patient_receipt set confirmed_sequence=? where origin_server_id=?")) {
                    q.setLong(1, event.sequence); q.setString(2, event.origin); q.executeUpdate();
                }
            }
            return event.sequence;
        });
    }
	
	public long confirmed(String origin) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> confirmed(connection, origin));
    }
	
	private long confirmed(Connection connection, String origin) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select confirmed_sequence from synchronizationmr_patient_receipt where origin_server_id = ?")) {
            query.setString(1, origin);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? rows.getLong(1) : 0L; }
        }
    }
	
	public long receive(PatientIncomingEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new APIException("La recepción requiere una transacción de escritura");
        }
        // El mismo bloqueo local serializa creación e importación, incluso la primera recepción.
        // En esta primera versión se prioriza la corrección sobre la recepción paralela por origen.
        String local = localNodeDao.getLocalServerId();
        if (local.equals(event.origin)) { throw new APIException("No se importan como remotos eventos del propio origen"); }
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            long confirmed = confirmed(connection, event.origin);
            if (event.sequence <= confirmed) {
                try (PreparedStatement query = connection.prepareStatement(
                        "select patient_uuid, event_uuid, payload_json from " + PatientSyncDao.EVENTS
                        + " where origin_server_id = ? and entity_sequence = ?")) {
                    query.setString(1, event.origin);
                    query.setLong(2, event.sequence);
                    try (ResultSet rows = query.executeQuery()) {
                        if (rows.next() && event.patientUuid.equals(rows.getString(1))
                                && event.eventUuid.equals(rows.getString(2)) && event.sameContent(rows.getString(3))) {
                            return confirmed;
                        }
                    }
                }
                throw new APIException("La secuencia ya confirmada no coincide con el evento recibido");
            }
            if (confirmed == Long.MAX_VALUE || event.sequence != confirmed + 1) {
                throw new APIException("La recepción debe continuar desde la última secuencia consecutiva confirmada");
            }
            // No sobrescribir ni vincular silenciosamente pacientes preexistentes sin una correspondencia validada.
            if (Context.getPersonService().getPersonByUuid(event.patientUuid) != null) {
                throw new APIException("El paciente o persona ya existe sin esta recepción confirmada; se requiere conciliación");
            }
            try (PreparedStatement check = connection.prepareStatement("select event_uuid from synchronizationmr_patient_update where event_uuid=?")) {
                check.setString(1, event.eventUuid);
                try (ResultSet rows = check.executeQuery()) {
                    if (rows.next()) { throw new APIException("Event UUID already used by UPDATE"); }
                }
            }
            Patient incoming = event.toPatient();
            Patient saved = IncomingPatientSave.save(incoming, () -> Context.getPatientService().savePatient(incoming));
            sessionFactory.getCurrentSession().flush();
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into synchronizationmr_patient_identity (patient_id, patient_uuid, origin_server_id, entity_sequence) values (?, ?, ?, ?)")) {
                insert.setInt(1, saved.getPatientId()); insert.setString(2, event.patientUuid);
                insert.setString(3, event.origin); insert.setLong(4, event.sequence); insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into synchronizationmr_patient_event (event_uuid, patient_id, operation, state, date_created, payload_json)"
                    + " values (?, ?, 'CREATE', 'PENDING', ?, ?)")) {
                insert.setString(1, event.eventUuid); insert.setInt(2, saved.getPatientId());
                insert.setTimestamp(3, new Timestamp(event.occurredAt.getTime())); insert.setString(4, event.json); insert.executeUpdate();
            }
            // PENDING conserva el evento para otros destinos; la recepción local se confirma en su propia tabla.
            if (confirmed == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "insert into synchronizationmr_patient_receipt (origin_server_id, confirmed_sequence) values (?, ?)")) {
                    insert.setString(1, event.origin); insert.setLong(2, event.sequence); insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement(
                        "update synchronizationmr_patient_receipt set confirmed_sequence = ? where origin_server_id = ?")) {
                    update.setLong(1, event.sequence); update.setString(2, event.origin); update.executeUpdate();
                }
            }
            return event.sequence;
        });
    }
}

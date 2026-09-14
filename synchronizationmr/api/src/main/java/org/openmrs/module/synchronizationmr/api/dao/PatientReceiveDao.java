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
	
	@Autowired
	private SessionFactory sessionFactory;
	
	@Autowired
	private LocalNodeDao localNodeDao;
	
	public long confirmed(String origin) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> confirmed(connection, origin));
    }
	
	private long confirmed(Connection connection, String origin) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select confirmed_sequence from synchronizationmr_patient_receipt where origin_node_uuid = ?")) {
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
        String local = localNodeDao.getOrCreateNodeUuid();
        if (local.equals(event.origin)) { throw new APIException("No se importan como remotos eventos del propio origen"); }
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            long confirmed = confirmed(connection, event.origin);
            if (event.sequence <= confirmed) {
                try (PreparedStatement query = connection.prepareStatement(
                        "select i.patient_uuid, e.event_uuid, e.payload_json from synchronizationmr_patient_identity i"
                        + " join synchronizationmr_patient_event e on e.patient_id = i.patient_id"
                        + " where i.origin_node_uuid = ? and i.entity_sequence = ?")) {
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
            Patient incoming = event.toPatient();
            Patient saved = IncomingPatientSave.save(incoming, () -> Context.getPatientService().savePatient(incoming));
            sessionFactory.getCurrentSession().flush();
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into synchronizationmr_patient_identity (patient_id, patient_uuid, origin_node_uuid, entity_sequence) values (?, ?, ?, ?)")) {
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
                        "insert into synchronizationmr_patient_receipt (origin_node_uuid, confirmed_sequence) values (?, ?)")) {
                    insert.setString(1, event.origin); insert.setLong(2, event.sequence); insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement(
                        "update synchronizationmr_patient_receipt set confirmed_sequence = ? where origin_node_uuid = ?")) {
                    update.setLong(1, event.sequence); update.setString(2, event.origin); update.executeUpdate();
                }
            }
            return event.sequence;
        });
    }
}

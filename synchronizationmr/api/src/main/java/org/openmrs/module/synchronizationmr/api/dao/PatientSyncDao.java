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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.openmrs.Patient;
import org.openmrs.module.synchronizationmr.sync.PatientSyncRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Usa la conexión actual de Hibernate: paciente, contador, identidad y evento se confirman o se
 * deshacen juntos. Este DAO solo escribe en las tablas propias del módulo.
 */
@Repository("synchronizationmr.PatientSyncDao")
public class PatientSyncDao {
	
	public java.util.List<String> findPatientOrigins(String afterOrigin, int limit) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            java.util.List<String> origins = new java.util.ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "select distinct origin_node_uuid from synchronizationmr_patient_identity where origin_node_uuid > ? order by origin_node_uuid")) {
                query.setString(1, afterOrigin); query.setMaxRows(limit);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) { origins.add(rows.getString(1)); }
                }
            }
            return java.util.Collections.unmodifiableList(origins);
        });
    }
	
	public long findHighestPatientSequence(String origin) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "select coalesce(max(entity_sequence), 0) from synchronizationmr_patient_identity where origin_node_uuid = ?")) {
                query.setString(1, origin);
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        });
    }
	
	public java.util.List<org.openmrs.module.synchronizationmr.sync.PatientSyncEvent> findPatientEventsAfter(
            String origin, long afterSequence, int limit) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            java.util.List<org.openmrs.module.synchronizationmr.sync.PatientSyncEvent> events = new java.util.ArrayList<>();
            // LEFT JOIN conserva identidades cuyo evento falte: un JOIN interno ocultaría ese fallo.
            // No filtramos por estado global: otro destino podría necesitar un evento ya entregado.
            try (PreparedStatement query = connection.prepareStatement(
                    "select i.entity_sequence, i.patient_uuid, e.event_uuid, e.payload_json"
                    + " from synchronizationmr_patient_identity i left join synchronizationmr_patient_event e"
                    + " on e.patient_id = i.patient_id where i.origin_node_uuid = ? and i.entity_sequence > ?"
                    + " order by i.entity_sequence asc")) {
                query.setString(1, origin);
                query.setLong(2, afterSequence);
                query.setMaxRows(limit);
                long previous = afterSequence;
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        long sequence = rows.getLong(1);
                        if (previous == Long.MAX_VALUE || sequence != previous + 1) {
                            throw new org.openmrs.api.APIException("No se puede entregar la página: falta la secuencia " + (previous + 1));
                        }
                        String eventUuid = rows.getString(3);
                        String payload = rows.getString(4);
                        if (eventUuid == null || payload == null || payload.trim().isEmpty()) {
                            throw new org.openmrs.api.APIException("No se puede entregar la página: falta el evento o su JSON en la secuencia " + sequence);
                        }
                        events.add(new org.openmrs.module.synchronizationmr.sync.PatientSyncEvent(
                                origin, sequence, eventUuid, rows.getString(2), payload));
                        previous = sequence;
                    }
                }
            }
            return java.util.Collections.unmodifiableList(events);
        });
    }
	
	@Autowired
	private SessionFactory sessionFactory;
	
	@Autowired
	private LocalNodeDao localNodeDao;
	
	@Autowired
	private org.openmrs.module.synchronizationmr.sync.PatientCreationPayloadSerializer payloadSerializer;
	
	/** Consulta separada porque el JSON sí contiene datos personales. */
	public String findCreationPayload(String patientUuid) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "select e.payload_json from synchronizationmr_patient_event e join synchronizationmr_patient_identity i"
                    + " on i.patient_id = e.patient_id where i.patient_uuid = ?")) {
                query.setString(1, patientUuid);
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        });
    }
	
	public boolean patientExists(Integer patientId) {
        if (patientId == null) {
            return false;
        }
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select patient_id from patient where patient_id = ?")) {
                statement.setInt(1, patientId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next();
                }
            }
        });
    }
	
	public PatientSyncRecord findByPatientUuid(String uuid, String label) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> find(connection, uuid, label, false));
    }
	
	public PatientSyncRecord recordCreation(Patient patient, String label) {
        // Guarda los cambios pendientes de Hibernate para poder enlazar nuestra fila al paciente.
        sessionFactory.getCurrentSession().flush();
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            String nodeUuid = localNodeDao.getOrCreateNodeUuid();
            long previous;
            // La única fila del contador se bloquea hasta confirmar el guardado.
            // Así, dos registros simultáneos no reciben el mismo número.
            try (PreparedStatement statement = connection.prepareStatement(
                    "select node_uuid, patient_sequence from synchronizationmr_local_node where singleton_id = 1 for update");
                    ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Falta la fila del nodo de sincronización; revise las migraciones del módulo");
                }
                previous = rows.getLong(2);
            }
            // Consulta el registro actualizado bajo bloqueo para evitar duplicar la misma identidad.
            PatientSyncRecord existing = find(connection, patient.getUuid(), label, true);
            if (existing != null) {
                return existing;
            }
            long sequence = Math.addExact(previous, 1L);
            try (PreparedStatement statement = connection.prepareStatement(
                    "update synchronizationmr_local_node set patient_sequence = ? where singleton_id = 1")) {
                statement.setLong(1, sequence);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into synchronizationmr_patient_identity (patient_id, patient_uuid, origin_node_uuid, entity_sequence) values (?, ?, ?, ?)")) {
                statement.setInt(1, patient.getPatientId());
                statement.setString(2, patient.getUuid());
                statement.setString(3, nodeUuid);
                statement.setLong(4, sequence);
                statement.executeUpdate();
            }
            String eventUuid = UUID.randomUUID().toString();
            Timestamp created = new Timestamp(System.currentTimeMillis());
            // Se construye una sola vez, dentro de la transacción y después de descartar duplicados.
            String payload = payloadSerializer.serialize(patient, nodeUuid, sequence, eventUuid, created);
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into synchronizationmr_patient_event (event_uuid, patient_id, operation, state, date_created, payload_json) values (?, ?, 'CREATE', 'PENDING', ?, ?)")) {
                statement.setString(1, eventUuid);
                statement.setInt(2, patient.getPatientId());
                statement.setTimestamp(3, created);
                statement.setString(4, payload);
                statement.executeUpdate();
            }
            return new PatientSyncRecord(nodeUuid, label, sequence, patient.getUuid(), eventUuid, "PENDING", created);
        });
    }
	
	private PatientSyncRecord find(Connection connection, String uuid, String label, boolean lock) throws SQLException {
        String sql = "select i.origin_node_uuid, i.entity_sequence, i.patient_uuid, e.event_uuid, e.state, e.date_created"
                + " from synchronizationmr_patient_identity i join synchronizationmr_patient_event e on e.patient_id = i.patient_id"
                + " where i.patient_uuid = ?" + (lock ? " for update" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new PatientSyncRecord(rows.getString(1), label, rows.getLong(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getTimestamp(6));
            }
        }
    }
}

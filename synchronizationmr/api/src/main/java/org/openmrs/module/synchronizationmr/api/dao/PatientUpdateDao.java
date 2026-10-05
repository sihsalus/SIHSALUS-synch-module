package org.openmrs.module.synchronizationmr.api.dao;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.SessionFactory;
import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.stereotype.Repository;

/** Usa el mismo bloqueo del nodo local y la misma transacción clínica que CREATE y la recepción. */
@Repository("synchronizationmr.PatientUpdateDao")
public class PatientUpdateDao {
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessionFactory;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao localNodeDao;
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	public void lock() {
		localNodeDao.getLocalServerId();
	}
	
	public void recordSavedChild(Integer patientId) {
		sessionFactory.getCurrentSession().flush();
		Patient patient = Context.getPatientService().getPatient(patientId);
		// Los servicios de registros asociados pueden no actualizar la colección del paciente ya cargado.
		sessionFactory.getCurrentSession().refresh(patient);
		recordSaved(patient);
	}
	
	public void recordSaved(Patient patient) {
        if (Boolean.TRUE.equals(patient.getVoided())) { return; } // VOID/MERGE corresponde a una operación independiente.
        String origin = localNodeDao.getLocalServerId();
        sessionFactory.getCurrentSession().flush();
        sessionFactory.getCurrentSession().doWork(connection -> {
            State state = state(connection, patient.getPatientId());
            if (state == null) { return; } // Paciente aún no preparado para la sincronización.
            ObjectNode snapshot = PatientSnapshot.capture(patient).toJson();
            if (!snapshot.path("patientUuid").equals(state.snapshot.path("patientUuid"))) {
                throw new APIException("Cannot change the UUID of a synchronized patient");
            }
            Set<String> changed = PatientUpdateEvent.changes(state.snapshot, snapshot);
            if (changed.isEmpty()) { return; }
            long sequence;
            try (PreparedStatement q = connection.prepareStatement("select patient_sequence from synchronizationmr_local_node where singleton_id=1");
                    ResultSet rows = q.executeQuery()) {
                if (!rows.next()) { throw new APIException("Missing local node"); }
                sequence = Math.addExact(rows.getLong(1), 1);
            }
            long time = System.currentTimeMillis();
            // Conserva el orden causal de una edición local posterior a un evento recibido, incluso en el mismo milisegundo.
            for (JsonNode version : state.versions) {
                time = Math.max(time, Math.addExact(Instant.parse(version.path("occurredAt").asText()).toEpochMilli(), 1));
            }
            PatientUpdateEvent event = new PatientUpdateEvent(PatientUpdateEvent.create(snapshot, changed, origin, sequence, Instant.ofEpochMilli(time)));
            // Valida antes de publicar, con los mismos campos permitidos y comprobaciones de referencias que la recepción.
            event.snapshotEvent().toPatientForUpdate();
            insert(connection, patient.getPatientId(), event);
            for (String group : changed) { state.versions.set(group, event.version()); }
            saveState(connection, patient.getPatientId(), snapshot, state.versions, state.persisted);
            try (PreparedStatement q = connection.prepareStatement("update synchronizationmr_local_node set patient_sequence=? where singleton_id=1")) {
                q.setLong(1, sequence); q.executeUpdate();
            }
        });
    }
	
	public void receive(Connection connection, PatientUpdateEvent event) throws SQLException {
        if (!Context.hasPrivilege(org.openmrs.util.PrivilegeConstants.EDIT_PATIENTS)
            || !Context.hasPrivilege(org.openmrs.util.PrivilegeConstants.EDIT_PERSONS)) {
            throw new org.openmrs.api.APIAuthenticationException("Patient updates require patient and person edit privileges");
        }
        int patientId;
        try (PreparedStatement q = connection.prepareStatement("select patient_id from synchronizationmr_patient_identity where patient_uuid=?")) {
            q.setString(1, event.patientUuid);
            try (ResultSet rows = q.executeQuery()) {
                if (!rows.next()) { throw new APIException("Receive the original patient before its updates"); }
                patientId = rows.getInt(1);
            }
        }
        Patient patient = Context.getPatientService().getPatient(patientId);
        if (patient == null || Boolean.TRUE.equals(patient.getVoided())) { throw new APIException("Cannot update a missing or voided patient"); }
        State state = state(connection, patientId);
        if (state == null) { throw new APIException("Missing original patient event"); }
        Patient incoming = event.snapshotEvent().toPatientForUpdate();
        Set<String> winning = new LinkedHashSet<>();
        for (String group : event.changedGroups) {
            if (PatientUpdateEvent.compare(event.version(), state.versions.get(group)) > 0) {
                winning.add(group);
                state.versions.set(group, event.version());
            }
        }
        if (!winning.isEmpty()) {
            IncomingPatientSave.save(patient, () -> {
                PatientUpdateApply.apply(patient, incoming, winning);
                return Context.getPatientService().savePatient(patient);
            });
            sessionFactory.getCurrentSession().flush();
        }
        // Una actualización anterior también se almacena y confirma, pero nunca se aplica sobre una más reciente.
        insert(connection, patientId, event);
        saveState(connection, patientId, PatientSnapshot.capture(patient).toJson(), state.versions, state.persisted);
    }
	
	private void insert(Connection connection, int patientId, PatientUpdateEvent event) throws SQLException {
        try (PreparedStatement check = connection.prepareStatement("select event_uuid from synchronizationmr_patient_event where event_uuid=?")) {
            check.setString(1, event.eventUuid);
            try (ResultSet rows = check.executeQuery()) {
                if (rows.next()) { throw new APIException("Event UUID already used by CREATE"); }
            }
        }
        try (PreparedStatement q = connection.prepareStatement("insert into synchronizationmr_patient_update (event_uuid,patient_id,origin_server_id,entity_sequence,payload_json) values (?,?,?,?,?)")) {
            q.setString(1, event.eventUuid); q.setInt(2, patientId); q.setString(3, event.origin);
            q.setLong(4, event.sequence); q.setString(5, event.json); q.executeUpdate();
        }
    }
	
	private State state(Connection connection, int patientId) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("select snapshot_json,versions_json from synchronizationmr_patient_state where patient_id=?")) {
            q.setInt(1, patientId);
            try (ResultSet rows = q.executeQuery()) {
                if (rows.next()) { return new State(object(rows.getString(1)), object(rows.getString(2)), true); }
            }
        }
        try (PreparedStatement q = connection.prepareStatement("select payload_json from synchronizationmr_patient_event where patient_id=?")) {
            q.setInt(1, patientId);
            try (ResultSet rows = q.executeQuery()) {
                if (!rows.next()) { return null; }
                ObjectNode event = object(rows.getString(1));
                ObjectNode versions = MAPPER.createObjectNode();
                for (String group : PatientUpdateEvent.GROUPS) { versions.set(group, PatientUpdateEvent.version(event)); }
                ObjectNode payload = (ObjectNode) event.path("payload");
                // Los eventos CREATE v3 históricos omitían estos campos; no se inventan valores históricos.
                return new State(canonical(payload), versions, false);
            }
        }
    }
	
	private ObjectNode canonical(ObjectNode snapshot) {
        ObjectNode result = snapshot.deepCopy();
        for (String field : Arrays.asList("names", "addresses", "identifiers", "attributes")) {
            if (!result.has(field)) { continue; }
            Map<String, JsonNode> values = new TreeMap<>();
            for (JsonNode item : result.path(field)) { values.put(item.path("uuid").asText(), item); }
            com.fasterxml.jackson.databind.node.ArrayNode array = result.putArray(field);
            for (JsonNode value : values.values()) { array.add(value); }
        }
        return result;
    }
	
	private ObjectNode object(String json) {
		try {
			JsonNode value = MAPPER.readTree(json);
			if (!value.isObject()) {
				throw new APIException("Invalid stored patient state");
			}
			return (ObjectNode) value;
		}
		catch (Exception e) {
			throw new APIException("Invalid or missing stored patient state");
		}
	}
	
	private void saveState(Connection c, int id, ObjectNode snapshot, ObjectNode versions, boolean exists) throws SQLException {
        String sql = exists ? "update synchronizationmr_patient_state set snapshot_json=?,versions_json=? where patient_id=?"
            : "insert into synchronizationmr_patient_state (snapshot_json,versions_json,patient_id) values (?,?,?)";
        try (PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, snapshot.toString()); q.setString(2, versions.toString()); q.setInt(3, id); q.executeUpdate();
        }
    }
	
	private static final class State {
		
		final ObjectNode snapshot, versions;
		
		final boolean persisted;
		
		State(ObjectNode snapshot, ObjectNode versions, boolean persisted) {
			this.snapshot = snapshot;
			this.versions = versions;
			this.persisted = persisted;
		}
	}
}

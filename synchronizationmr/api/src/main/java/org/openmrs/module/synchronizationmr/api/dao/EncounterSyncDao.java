package org.openmrs.module.synchronizationmr.api.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.openmrs.Encounter;
import org.openmrs.api.APIException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.openmrs.module.synchronizationmr.sync.EncounterEventStream;

/** Solo escribe en tablas del módulo. Comparte la transacción del encuentro clínico. */
@Repository("synchronizationmr.EncounterSyncDao")
public class EncounterSyncDao {
	
	private static final String PENDING = " from encounter c left join synchronizationmr_encounter_event e on e.encounter_id = c.encounter_id"
	        + " where c.voided = false and (e.event_uuid is null or e.payload_json is null or trim(e.payload_json) = '')";
	
	public java.util.List<Integer> lockAndFindPending(int limit) {
        sessionFactory.getCurrentSession().flush();
        localNodeDao.getLocalServerId();
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("select c.encounter_id" + PENDING + " order by c.encounter_id")) {
                query.setMaxRows(limit);
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) { ids.add(rows.getInt(1)); } }
            }
            return ids;
        });
    }
	
	public long countPending() {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            try (PreparedStatement query = connection.prepareStatement("select count(*)" + PENDING); ResultSet rows = query.executeQuery()) {
                rows.next(); return rows.getLong(1);
            }
        });
    }
	
	public void requirePayload(int id) {
        sessionFactory.getCurrentSession().doWork(connection -> {
            try (PreparedStatement query = connection.prepareStatement("select payload_json from synchronizationmr_encounter_event where encounter_id = ?")) {
                query.setInt(1, id);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next() || rows.getString(1) == null || rows.getString(1).trim().isEmpty()) {
                        throw new APIException("Evento histórico sin JSON; requiere revisión y no se reconstruye automáticamente");
                    }
                }
            }
        });
    }
	
	public java.util.List<String> findEncounterOrigins(String afterOrigin, int limit) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            java.util.List<String> origins = new java.util.ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "select distinct origin_server_id from " + EncounterEventStream.SQL + " e where origin_server_id > ? order by origin_server_id")) {
                query.setString(1, afterOrigin); query.setMaxRows(limit);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) { origins.add(rows.getString(1)); }
                }
            }
            return java.util.Collections.unmodifiableList(origins);
        });
    }
	
	public long findHighestEncounterSequence(String origin) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "select coalesce(max(entity_sequence), 0) from " + EncounterEventStream.SQL + " e where origin_server_id = ?")) {
                query.setString(1, origin);
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        });
    }
	
	public java.util.List<org.openmrs.module.synchronizationmr.sync.EncounterSyncEvent> findEncounterEventsAfter(
            String origin, long afterSequence, int limit) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            java.util.List<org.openmrs.module.synchronizationmr.sync.EncounterSyncEvent> events = new java.util.ArrayList<>();
            // No filtramos por estado global: otro destino podría necesitar un evento ya entregado.
            try (PreparedStatement query = connection.prepareStatement(
                    "select i.entity_sequence, i.encounter_uuid, i.event_uuid, i.payload_json"
                    + " from " + EncounterEventStream.SQL + " i"
                    + " where i.origin_server_id = ? and i.entity_sequence > ?"
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
                        events.add(new org.openmrs.module.synchronizationmr.sync.EncounterSyncEvent(
                                origin, sequence, eventUuid, rows.getString(2), payload));
                        previous = sequence;
                    }
                }
            }
            return java.util.Collections.unmodifiableList(events);
        });
    }
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessionFactory;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao localNodeDao;
	
	@javax.annotation.Resource(name = "synchronizationmr.EncounterCreationPayloadSerializer")
	private org.openmrs.module.synchronizationmr.sync.EncounterCreationPayloadSerializer serializer;
	
	public boolean exists(Integer id) {
        if (id == null) { return false; }
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select encounter_id from encounter where encounter_id = ?")) {
                statement.setInt(1, id);
                try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
            }
        });
    }
	
	public void capture(Encounter encounter) {
        sessionFactory.getCurrentSession().flush();
        sessionFactory.getCurrentSession().doWork(connection -> {
            String origin = localNodeDao.getLocalServerId();
            long previous;
            try (PreparedStatement statement = connection.prepareStatement(
                    "select encounter_sequence from synchronizationmr_local_node where singleton_id = 1 for update");
                    ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) { throw new APIException("Falta la fila del nodo local"); }
                previous = rows.getLong(1);
            }
            // Bajo el mismo bloqueo: una llamada repetida no consume otra secuencia.
            try (PreparedStatement statement = connection.prepareStatement(
                    "select event_uuid from synchronizationmr_encounter_event where encounter_id = ? for update")) {
                statement.setInt(1, encounter.getEncounterId());
                try (ResultSet rows = statement.executeQuery()) { if (rows.next()) { return; } }
            }
            long sequence = Math.addExact(previous, 1L);
            String eventUuid = UUID.randomUUID().toString();
            Timestamp created = new Timestamp(System.currentTimeMillis());
            String payload = serializer.serialize(encounter, origin, sequence, eventUuid, created);
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into synchronizationmr_encounter_event (event_uuid, encounter_id, encounter_uuid, patient_id, patient_uuid, origin_server_id, entity_sequence, operation, state, date_created, payload_json) values (?, ?, ?, ?, ?, ?, ?, 'CREATE', 'PENDING', ?, ?)")) {
                statement.setString(1, eventUuid);
                statement.setInt(2, encounter.getEncounterId());
                statement.setString(3, encounter.getUuid());
                statement.setInt(4, encounter.getPatient().getPatientId());
                statement.setString(5, encounter.getPatient().getUuid());
                statement.setString(6, origin);
                statement.setLong(7, sequence);
                statement.setTimestamp(8, created);
                statement.setString(9, payload);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "update synchronizationmr_local_node set encounter_sequence = ? where singleton_id = 1")) {
                statement.setLong(1, sequence);
                statement.executeUpdate();
            }
        });
    }
	
	public java.util.List<Integer> findPublishedEncountersAfter(int after, int limit) {
        sessionFactory.getCurrentSession().flush();
        localNodeDao.getLocalServerId();
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "select c.encounter_id from encounter c join synchronizationmr_encounter_event e on e.encounter_id = c.encounter_id"
                    + " where c.voided = false and c.encounter_id > ? order by c.encounter_id")) {
                query.setInt(1, after); query.setMaxRows(limit);
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) ids.add(rows.getInt(1)); }
            }
            return ids;
        });
    }
	
	public void captureAdditions(Encounter encounter) {
		captureAdditions(encounter, false);
	}
	
	/** Only previously unpublished observations are added. Existing values are never overwritten. */
	public boolean captureAdditions(Encounter encounter, boolean strict) {
        sessionFactory.getCurrentSession().flush();
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            String origin = localNodeDao.getLocalServerId(); // serializes allocation and duplicate detection
            java.util.Set<String> known = new java.util.HashSet<>();
            boolean hasCreation = false;
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            // Current reads under the node lock also work with MariaDB REPEATABLE READ.
            // A consistent read could otherwise miss a concurrently committed capture.
            for (String table : new String[]{"synchronizationmr_encounter_event", "synchronizationmr_encounter_addition"}) {
            try (PreparedStatement query = connection.prepareStatement(
                    "select payload_json from " + table + " where encounter_id = ? for update")) {
                query.setInt(1, encounter.getEncounterId());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        try {
                            com.fasterxml.jackson.databind.JsonNode event = mapper.readTree(rows.getString(1));
                            if (event == null) throw new APIException("Falta el JSON original del encuentro");
                            hasCreation |= "CREATE".equals(event.path("operation").asText());
                            collectObservationIds(event.path("payload").path("obs"), known);
                        } catch (java.io.IOException failure) {
                            throw new APIException("JSON original de encuentro invalido", failure);
                        }
                    }
                }
            }
            }
            if (!hasCreation) {
                if (strict) throw new APIException("Falta el evento CREATE del encuentro");
                return false; // historical encounters still require explicit preparation
            }
            java.util.List<org.openmrs.Obs> added = new java.util.ArrayList<>();
            java.util.Set<String> newIds = new java.util.HashSet<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "select obs_id, uuid from obs where encounter_id = ? and voided = false and previous_version is null order by obs_id")) {
                query.setInt(1, encounter.getEncounterId());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (!known.contains(rows.getString(2))) {
                            added.add(org.openmrs.api.context.Context.getObsService().getObs(rows.getInt(1)));
                            newIds.add(rows.getString(2));
                        }
                    }
                }
            }
            if (added.isEmpty()) return false;
            // Do not export an orphan whose parent is an unpublished correction/voided group.
            for (org.openmrs.Obs obs : added) {
                if (obs.getObsGroup() != null && !known.contains(obs.getObsGroup().getUuid())
                        && !newIds.contains(obs.getObsGroup().getUuid())) {
                    if (strict) throw new APIException("Resultado con grupo no publicado; requiere revision antes de continuar");
                    return false;
                }
            }
            long sequence;
            try (PreparedStatement query = connection.prepareStatement(
                    "select encounter_sequence from synchronizationmr_local_node where singleton_id = 1");
                    ResultSet rows = query.executeQuery()) {
                rows.next(); sequence = Math.addExact(rows.getLong(1), 1L);
            }
            String eventUuid = UUID.randomUUID().toString();
            Timestamp created = new Timestamp(System.currentTimeMillis());
            String json = serializer.serializeAddition(encounter, added, newIds, origin, sequence, eventUuid, created);
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into synchronizationmr_encounter_addition (event_uuid,encounter_id,encounter_uuid,origin_server_id,entity_sequence,date_created,payload_json) values (?,?,?,?,?,?,?)")) {
                insert.setString(1,eventUuid); insert.setInt(2,encounter.getEncounterId()); insert.setString(3,encounter.getUuid());
                insert.setString(4,origin); insert.setLong(5,sequence); insert.setTimestamp(6,created); insert.setString(7,json);
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "update synchronizationmr_local_node set encounter_sequence = ? where singleton_id = 1")) {
                update.setLong(1,sequence); update.executeUpdate();
            }
            return true;
        });
    }
	
	private void collectObservationIds(com.fasterxml.jackson.databind.JsonNode items, java.util.Set<String> ids) {
		for (com.fasterxml.jackson.databind.JsonNode item : items) {
			ids.add(item.path("uuid").asText());
			collectObservationIds(item.path("groupMembers"), ids);
		}
	}
}

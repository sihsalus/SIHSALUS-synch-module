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

/** Solo escribe en tablas del módulo. Comparte la transacción del encuentro clínico. */
@Repository("synchronizationmr.EncounterSyncDao")
public class EncounterSyncDao {
	
	@Autowired
	private SessionFactory sessionFactory;
	
	@Autowired
	private LocalNodeDao localNodeDao;
	
	@Autowired
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
}

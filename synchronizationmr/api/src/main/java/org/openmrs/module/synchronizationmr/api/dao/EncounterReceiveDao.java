package org.openmrs.module.synchronizationmr.api.dao;

import java.sql.*;
import org.hibernate.SessionFactory;
import org.openmrs.Encounter;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Encounter, observations, original event and receipt commit together. */
@Repository("synchronizationmr.EncounterReceiveDao")
public class EncounterReceiveDao {
	
	@Autowired
	private SessionFactory sessionFactory;
	
	@Autowired
	private LocalNodeDao localNodeDao;
	
	public long confirmed(String origin) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> confirmed(connection, origin));
    }
	
	private long confirmed(Connection connection, String origin) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select confirmed_sequence from synchronizationmr_encounter_receipt where origin_server_id = ?")) {
            query.setString(1, origin);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        }
    }
	
	public long receive(EncounterIncomingEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) { throw new APIException("La recepción requiere transacción de escritura"); }
        if (localNodeDao.getLocalServerId().equals(event.origin)) { throw new APIException("No se importa el propio origen"); }
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            long confirmed = confirmed(connection, event.origin);
            if (event.sequence <= confirmed) {
                try (PreparedStatement query = connection.prepareStatement(
                        "select encounter_uuid, event_uuid, payload_json from synchronizationmr_encounter_event where origin_server_id = ? and entity_sequence = ?")) {
                    query.setString(1, event.origin); query.setLong(2, event.sequence);
                    try (ResultSet rows = query.executeQuery()) {
                        if (rows.next() && event.encounterUuid.equals(rows.getString(1)) && event.eventUuid.equals(rows.getString(2))
                                && event.sameContent(rows.getString(3))) { return confirmed; }
                    }
                }
                throw new APIException("El evento no coincide con la secuencia ya confirmada");
            }
            if (confirmed == Long.MAX_VALUE || event.sequence != confirmed + 1) { throw new APIException("Falta una secuencia anterior de encuentros"); }
            if (Context.getEncounterService().getEncounterByUuid(event.encounterUuid) != null) { throw new APIException("El encuentro ya existe sin esta recepción confirmada"); }
            Encounter incoming = event.toEncounter();
            Encounter saved = IncomingEncounterSave.save(incoming, () -> Context.getEncounterService().saveEncounter(incoming));
            sessionFactory.getCurrentSession().flush();
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into synchronizationmr_encounter_event (event_uuid, encounter_id, encounter_uuid, patient_id, patient_uuid, origin_server_id, entity_sequence, operation, state, date_created, payload_json) values (?, ?, ?, ?, ?, ?, ?, 'CREATE', 'PENDING', ?, ?)")) {
                insert.setString(1, event.eventUuid); insert.setInt(2, saved.getEncounterId()); insert.setString(3, event.encounterUuid);
                insert.setInt(4, saved.getPatient().getPatientId()); insert.setString(5, event.patientUuid);
                insert.setString(6, event.origin); insert.setLong(7, event.sequence); insert.setTimestamp(8, new Timestamp(event.occurredAt.getTime()));
                insert.setString(9, event.json); insert.executeUpdate();
            }
            if (confirmed == 0) {
                try (PreparedStatement insert = connection.prepareStatement("insert into synchronizationmr_encounter_receipt (origin_server_id, confirmed_sequence) values (?, ?)")) {
                    insert.setString(1, event.origin); insert.setLong(2, event.sequence); insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement("update synchronizationmr_encounter_receipt set confirmed_sequence = ? where origin_server_id = ?")) {
                    update.setLong(1, event.sequence); update.setString(2, event.origin); update.executeUpdate();
                }
            }
            return event.sequence;
        });
    }
}

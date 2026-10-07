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

/** El contenido clÃ­nico, el evento original y el recibo se confirman en la misma transacciÃ³n. */
@Repository("synchronizationmr.EncounterReceiveDao")
public class EncounterReceiveDao {
	
	@javax.annotation.Resource(name = "synchronizationmr.VisitUpdateDao")
	private VisitUpdateDao visits;
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessionFactory;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao localNodeDao;
	
	@javax.annotation.Resource(name = "synchronizationmr.OrderLinkDao")
	private OrderLinkDao orderLinks;
	
	@javax.annotation.Resource(name = "synchronizationmr.EncounterUpdateDao")
	private EncounterUpdateDao updates;
	
	@javax.annotation.Resource(name = "synchronizationmr.ObservationCorrectionDao")
	private ObservationCorrectionDao corrections;
	
	@javax.annotation.Resource(name = "synchronizationmr.ObservationVoidDao")
	private ObservationVoidDao voids;
	
	@javax.annotation.Resource(name = "synchronizationmr.EncounterVoidDao")
	private EncounterVoidDao annulments;
	
	public long confirmed(String origin) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> confirmed(connection, origin));
    }
	
	private long confirmed(Connection connection, String origin) throws SQLException {
		return confirmed(connection, origin, false);
	}
	
	private long confirmed(Connection connection, String origin, boolean lock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select confirmed_sequence from synchronizationmr_encounter_receipt where origin_server_id = ?" + (lock ? " for update" : ""))) {
            query.setString(1, origin);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        }
    }
	
	public long receive(EncounterIncomingEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) { throw new APIException("La recepciÃ³n requiere transacciÃ³n de escritura"); }
        if (localNodeDao.getLocalServerId().equals(event.origin)) { throw new APIException("No se importa el propio origen"); }
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            long confirmed = confirmed(connection, event.origin, true);
            if (event.sequence <= confirmed) {
                try (PreparedStatement query = connection.prepareStatement(
                        "select encounter_uuid, event_uuid, payload_json from " + EncounterEventStream.SQL + " e where origin_server_id = ? and entity_sequence = ?")) {
                    query.setString(1, event.origin); query.setLong(2, event.sequence);
                    try (ResultSet rows = query.executeQuery()) {
                        if (rows.next() && event.encounterUuid.equals(rows.getString(1)) && event.eventUuid.equals(rows.getString(2))
                                && event.sameContent(EncounterVisitSupplementDao.wire(connection,event.eventUuid,rows.getString(3)))) { return confirmed; }
                    }
                }
                throw new APIException("El evento no coincide con la secuencia ya confirmada");
            }
            if (confirmed == Long.MAX_VALUE || event.sequence != confirmed + 1) { throw new APIException("Falta una secuencia anterior de encuentros"); }
            for (String table : new String[]{"synchronizationmr_encounter_event", "synchronizationmr_encounter_addition", "synchronizationmr_encounter_update", "synchronizationmr_encounter_correction", "synchronizationmr_encounter_void", "synchronizationmr_encounter_annulment", "synchronizationmr_visit_update"}) {
            try (PreparedStatement query = connection.prepareStatement(
                    "select event_uuid from " + table + " where event_uuid = ? for update")) {
                query.setString(1, event.eventUuid);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) throw new APIException("UUID de evento ya utilizado");
                }
            }
            }
            if (event.visitVoid) {
                Context.getRegisteredComponent("synchronizationmr.VisitVoidDao",VisitVoidDao.class).receive(connection,new VisitVoidEvent(event.json));
            } else if (event.visitUpdate) {
                visits.receive(connection,new VisitUpdateEvent(event.json));
            } else if (event.voidEncounter) {
                annulments.receive(connection,new EncounterVoidEvent(event.json));
            } else if (event.voidObservations) {
                voids.receive(connection,new ObservationVoidEvent(event.json));
            } else if (event.correction) {
                corrections.receive(connection, new ObservationCorrectionEvent(event.json));
            } else if (event.update) {
                updates.receive(connection, new EncounterUpdateEvent(event.json));
            } else if (event.addition) {
                receiveAddition(connection, event);
            } else {
            if (Context.getEncounterService().getEncounterByUuid(event.encounterUuid) != null) { throw new APIException("El encuentro ya existe sin esta recepciÃ³n confirmada"); }
            Encounter incoming = event.toEncounter();
            EncounterObservationVersions versions = new EncounterObservationVersions(event);
            if (incoming.getVisit() != null && incoming.getVisit().getVisitId() == null) {
                incoming.setVisit(Context.getVisitService().saveVisit(incoming.getVisit()));
            }
            Encounter saved = IncomingEncounterSave.save(incoming, () -> Context.getEncounterService().saveEncounter(incoming));
            sessionFactory.getCurrentSession().flush();
            versions.apply(sessionFactory.getCurrentSession(), connection);
            orderLinks.record(event);
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into synchronizationmr_encounter_event (event_uuid, encounter_id, encounter_uuid, patient_id, patient_uuid, origin_server_id, entity_sequence, operation, state, date_created, payload_json) values (?, ?, ?, ?, ?, ?, ?, 'CREATE', 'PENDING', ?, ?)")) {
                insert.setString(1, event.eventUuid); insert.setInt(2, saved.getEncounterId()); insert.setString(3, event.encounterUuid);
                insert.setInt(4, saved.getPatient().getPatientId()); insert.setString(5, event.patientUuid);
                insert.setString(6, event.origin); insert.setLong(7, event.sequence); insert.setTimestamp(8, new Timestamp(event.occurredAt.getTime()));
                insert.setString(9, event.json); insert.executeUpdate();
            }
            }
            // Una edicion tardia se conserva, pero no reactiva un encuentro anulado.
            Encounter affected = Context.getEncounterService().getEncounterByUuid(event.encounterUuid);
            Context.getRegisteredComponent("synchronizationmr.VisitVoidDao",VisitVoidDao.class).preserve(affected);
            annulments.preserveAnnulment(connection,affected);
            EncounterVisitSupplementDao.record(connection,event);
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
	
	private void receiveAddition(Connection connection, EncounterIncomingEvent event) throws SQLException {
        Encounter encounter = Context.getEncounterService().getEncounterByUuid(event.encounterUuid);
        if (encounter == null) throw new EncounterDependencyException("ENCOUNTER_NOT_AVAILABLE: falta el encuentro de los resultados");
        java.util.List<org.openmrs.Obs> observations = event.addedObservations(encounter);
        IncomingEncounterSave.save(encounter, () -> {
            for (org.openmrs.Obs obs : observations) Context.getObsService().saveObs(obs, null);
            return encounter;
        });
        sessionFactory.getCurrentSession().flush();
        orderLinks.record(event);
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into synchronizationmr_encounter_addition (event_uuid,encounter_id,encounter_uuid,origin_server_id,entity_sequence,date_created,payload_json) values (?,?,?,?,?,?,?)")) {
            insert.setString(1,event.eventUuid); insert.setInt(2,encounter.getEncounterId()); insert.setString(3,event.encounterUuid);
            insert.setString(4,event.origin); insert.setLong(5,event.sequence); insert.setTimestamp(6,new Timestamp(event.occurredAt.getTime()));
            insert.setString(7,event.json); insert.executeUpdate();
        }
    }
}

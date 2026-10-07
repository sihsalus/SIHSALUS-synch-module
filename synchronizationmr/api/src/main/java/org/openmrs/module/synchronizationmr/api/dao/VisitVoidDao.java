package org.openmrs.module.synchronizationmr.api.dao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Instant;
import java.util.Date;
import org.hibernate.SessionFactory;
import org.openmrs.Encounter;
import org.openmrs.Visit;
import org.openmrs.api.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.stereotype.Repository;

/** Publica la anulación de una visita y aplica la cascada nativa sin recapturar sus hijos. */
@Repository("synchronizationmr.VisitVoidDao")
public class VisitVoidDao {
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessions;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao node;
	
	private static final ObjectMapper M = new ObjectMapper();
	
	public void capture(Visit visit) {
        sessions.getCurrentSession().flush();
        sessions.getCurrentSession().doWork(c -> {
            String anchor = anchor(c, visit);
            if (anchor == null || published(c,visit)) return;
            long sequence;
            try (PreparedStatement q=c.prepareStatement("select encounter_sequence from synchronizationmr_local_node where singleton_id=1"); ResultSet r=q.executeQuery()) {
                if (!r.next()) throw new APIException("Falta la identidad local");
                sequence=Math.addExact(r.getLong(1),1);
            }
            VisitVoidEvent event = new VisitVoidEvent(VisitVoidEvent.create(visit,anchor,node.getLocalServerId(),sequence,Instant.ofEpochMilli(System.currentTimeMillis())));
            insert(c,event);
            try (PreparedStatement q=c.prepareStatement("update synchronizationmr_local_node set encounter_sequence=? where singleton_id=1")) {
                q.setLong(1,sequence); q.executeUpdate();
            }
        });
    }
	
	public void receive(Connection c, VisitVoidEvent event) throws SQLException {
        if (!Context.hasPrivilege("Delete Visits")) throw new APIAuthenticationException("La anulación de visitas requiere Delete Visits");
        Visit visit=Context.getVisitService().getVisitByUuid(event.visitUuid);
        Encounter encounter=Context.getEncounterService().getEncounterByUuid(event.encounterUuid);
        if (visit==null || encounter==null) throw new EncounterDependencyException("Falta la visita o el encuentro publicado");
        if (visit.getPatient().getVoided() || !event.patientUuid.equals(visit.getPatient().getUuid())
            || !event.patientUuid.equals(encounter.getPatient().getUuid()) || encounter.getVisit()==null
            || !event.visitUuid.equals(encounter.getVisit().getUuid())) throw new APIException("La visita no corresponde al paciente y encuentro");
        try (PreparedStatement q=c.prepareStatement("select payload_json from synchronizationmr_encounter_event where encounter_uuid=?")) {
            q.setString(1,event.encounterUuid);
            try(ResultSet r=q.executeQuery()) {
                if (!r.next() || !event.visitUuid.equals(parse(r.getString(1)).path("payload").path("visit").path("uuid").asText()))
                    throw new EncounterDependencyException("El encuentro no publicó esta visita");
            }
        }
        if (!visit.getVoided()) {
            visit.setDateVoided(Date.from(event.dateVoided));
            try (VisitAnnulmentScope scope=VisitAnnulmentScope.enter(visit)) {
                Context.getVisitService().voidVisit(visit,event.reason);
            }
            sessions.getCurrentSession().flush();
        }
        insert(c,event);
    }
	
	/** Solo una anulación publicada permite recibir contenido tardío como historial anulado. */
	public boolean isPublished(Visit visit) {
        return visit!=null && visit.getId()!=null && visit.getVoided()
            && sessions.getCurrentSession().doReturningWork(c -> published(c,visit));
    }
	
	public void preserve(Encounter encounter) {
        if (encounter==null || encounter.getVoided() || !isPublished(encounter.getVisit())) return;
        Visit visit=encounter.getVisit();
        encounter.setDateVoided(visit.getDateVoided());
        IncomingEncounterSave.save(encounter, () -> Context.getEncounterService().voidEncounter(encounter,visit.getVoidReason()));
        sessions.getCurrentSession().flush();
    }
	
	private boolean published(Connection c,Visit visit) throws SQLException {
        try (PreparedStatement q=c.prepareStatement("select u.payload_json from synchronizationmr_visit_update u join encounter e on e.encounter_id=u.encounter_id where e.visit_id=?")) {
            q.setInt(1,visit.getId());
            try(ResultSet r=q.executeQuery()) {
                while(r.next()) {
                    JsonNode event=parse(r.getString(1));
                    if ("VOID_VISIT".equals(event.path("operation").asText()) && visit.getUuid().equals(event.path("payload").path("visitUuid").asText())) return true;
                }
            }
        }
        return false;
    }
	
	private String anchor(Connection c,Visit visit) throws SQLException {
        try (PreparedStatement q=c.prepareStatement("select e.encounter_uuid,e.payload_json from synchronizationmr_encounter_event e join encounter n on n.encounter_id=e.encounter_id where n.visit_id=? order by e.event_uuid")) {
            q.setInt(1,visit.getId());
            try(ResultSet r=q.executeQuery()) {
                while(r.next()) if (visit.getUuid().equals(parse(r.getString(2)).path("payload").path("visit").path("uuid").asText())) return r.getString(1);
            }
        }
        return null;
    }
	
	private JsonNode parse(String json) {
		try {
			return M.readTree(json);
		}
		catch (Exception ex) {
			throw new APIException("Evento de visita inválido", ex);
		}
	}
	
	private void insert(Connection c,VisitVoidEvent e) throws SQLException {
        try (PreparedStatement q=c.prepareStatement("insert into synchronizationmr_visit_update (event_uuid,encounter_id,encounter_uuid,origin_server_id,entity_sequence,date_created,payload_json) values (?,(select encounter_id from encounter where uuid=?),?,?,?,?,?)")) {
            q.setString(1,e.eventUuid); q.setString(2,e.encounterUuid); q.setString(3,e.encounterUuid);
            q.setString(4,e.origin); q.setLong(5,e.sequence); q.setTimestamp(6,Timestamp.from(e.occurredAt)); q.setString(7,e.json); q.executeUpdate();
        }
    }
}

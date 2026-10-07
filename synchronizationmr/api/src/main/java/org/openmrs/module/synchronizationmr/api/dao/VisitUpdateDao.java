package org.openmrs.module.synchronizationmr.api.dao;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.time.Instant;
import java.util.Date;
import org.hibernate.SessionFactory;
import org.openmrs.*;
import org.openmrs.api.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.stereotype.Repository;

/** Los cambios de una visita publicada comparten transporte y recibos de encuentros. */
@Repository("synchronizationmr.VisitUpdateDao")
public class VisitUpdateDao {
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessions;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao node;
	
	@javax.annotation.Resource(name = "visitDAO")
	private org.openmrs.api.db.VisitDAO visits;
	
	private static final ObjectMapper M = new ObjectMapper();
	
	public void record(Visit visit) {
        if (visit==null || visit.getId()==null) return;
        if (visit.getVoided()) { Context.getRegisteredComponent("synchronizationmr.VisitVoidDao",VisitVoidDao.class).capture(visit); return; }
        sessions.getCurrentSession().flush();
        sessions.getCurrentSession().doWork(c -> {
            State state=state(c,visit);
            if (state==null) return; // Sin encuentro publicado, el primer CREATE llevará el estado actual.
            ObjectNode current=VisitUpdateEvent.current(visit);
            if (current.equals(state.snapshot)) return;
            String origin=node.getLocalServerId();long sequence;
            try(PreparedStatement q=c.prepareStatement("select encounter_sequence from synchronizationmr_local_node where singleton_id=1");ResultSet r=q.executeQuery()) {r.next();sequence=Math.addExact(r.getLong(1),1);}
            long time=System.currentTimeMillis();
            if (state.version!=null) time=Math.max(time,Math.addExact(Instant.parse(state.version.path("occurredAt").asText()).toEpochMilli(),1));
            VisitUpdateEvent event=new VisitUpdateEvent(VisitUpdateEvent.createFull(visit,state.encounter==null?anchor(c,visit):state.encounter,origin,sequence,Instant.ofEpochMilli(time)));
            ObjectNode version=event.version();version.put("metadataManaged",true);
            insert(c,visit,event);save(c,visit,current,version,state.persisted);
            try(PreparedStatement q=c.prepareStatement("update synchronizationmr_local_node set encounter_sequence=? where singleton_id=1")) {q.setLong(1,sequence);q.executeUpdate();}
        });
    }
	
	public void receive(Connection c, VisitUpdateEvent event) throws SQLException {
		if (!Context.hasPrivilege("Edit Visits"))
			throw new APIAuthenticationException("La modificación de visitas requiere Edit Visits");
		Encounter anchor = Context.getEncounterService().getEncounterByUuid(event.encounterUuid);
		Visit target = Context.getVisitService().getVisitByUuid(event.visitUuid);
		if (anchor == null || target == null)
			throw new EncounterDependencyException("Falta la visita o su encuentro publicado");
		if (target.getPatient().getVoided()
		        || !event.patientUuid.equals(target.getPatient().getUuid())
		        || !event.patientUuid.equals(anchor.getPatient().getUuid()) || anchor.getVisit() == null
		        || !event.visitUuid.equals(anchor.getVisit().getUuid()))
			throw new APIException("La visita no corresponde al encuentro y paciente indicados");
		try (PreparedStatement q=c.prepareStatement("select event_uuid from synchronizationmr_encounter_event where encounter_uuid=?")) {
            q.setString(1,event.encounterUuid);
            try (ResultSet rows=q.executeQuery()) { if (!rows.next()) throw new EncounterDependencyException("El encuentro de referencia aún no está publicado"); }
        }
        State state = state(c, target);
		if (state == null)
			throw new EncounterDependencyException("Falta el CREATE que publicó la visita");
		if (state.version == null || PatientUpdateEvent.compare(event.version(), state.version) > 0) {
			target.setStartDatetime(Date.from(Instant.parse(event.snapshot.path("startDatetime").asText())));
			JsonNode stop = event.snapshot.get("stopDatetime");
			target.setStopDatetime(stop.isNull() ? null : Date.from(Instant.parse(stop.asText())));
			if(event.full) VisitMetadata.apply(target,event.snapshot.path("metadata"),Date.from(event.occurredAt));
            // El DAO evita recaptura; no altera encuentros ni observaciones asociados.
			target.setChangedBy(Context.getAuthenticatedUser());
			target.setDateChanged(new Date());
			for (VisitAttribute attribute : target.getActiveAttributes()) org.openmrs.validator.ValidateUtil.validate(attribute);
            org.openmrs.validator.ValidateUtil.validate(target);
			visits.saveVisit(target);
			sessions.getCurrentSession().flush();
			ObjectNode version=event.version();
            if(event.full || (state.version!=null && state.version.path("metadataManaged").asBoolean())) version.put("metadataManaged",true);
            ObjectNode snapshot=VisitUpdateEvent.current(target);
            save(c, target, snapshot, version, state.persisted);
		}
		insert(c, target, event);
	}
	
	private State state(Connection c,Visit visit) throws SQLException {
        try(PreparedStatement q=c.prepareStatement("select snapshot_json,version_json from synchronizationmr_visit_state where visit_id=? for update")) {
            q.setInt(1,visit.getId());try(ResultSet r=q.executeQuery()) {if(r.next()) {
                ObjectNode snapshot=object(r.getString(1));
                if(!snapshot.has("metadata")) {
                    State initial=initial(c,visit);
                    if(initial==null) throw new EncounterDependencyException("Falta la instantánea original de la visita");
                    snapshot.set("metadata",initial.snapshot.get("metadata"));
                }
                return new State(null,snapshot,object(r.getString(2)),true);
            }}
        }
        return initial(c,visit);
    }
	
	private State initial(Connection c,Visit visit) throws SQLException {
        try(PreparedStatement q=c.prepareStatement("select e.encounter_uuid,e.payload_json,s.wire_json from synchronizationmr_encounter_event e join encounter n on n.encounter_id=e.encounter_id left join synchronizationmr_visit_supplement s on s.event_uuid=e.event_uuid where n.visit_id=? order by e.date_created,e.event_uuid for update")) {
            q.setInt(1,visit.getId());try(ResultSet r=q.executeQuery()) {while(r.next()) {
                JsonNode v=r.getString(3)==null ? object(r.getString(2)).path("payload").path("visit") : object(r.getString(3)).path("visitSupplement");
                if (!visit.getUuid().equals(v.path("uuid").asText())) continue;
                ObjectNode baseline=M.createObjectNode();
                for(String key:new String[]{"startDatetime","stopDatetime"}) {
                    JsonNode value=v.get(key);
                    if(value==null || value.isNull())baseline.putNull(key);
                    else baseline.put(key,Instant.parse(value.asText()).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
                }
                baseline.set("metadata",VisitMetadata.fromVisitSnapshot(v));
                return new State(r.getString(1),baseline,null,false);
            }}
        }
        return null;
    }
	
	public boolean hasCurrentInterval(Visit visit) {
        return sessions.getCurrentSession().doReturningWork(c -> {
            try(PreparedStatement q=c.prepareStatement("select snapshot_json from synchronizationmr_visit_state where visit_id=?")) {
                q.setInt(1,visit.getId());try(ResultSet rows=q.executeQuery()) {
                    if(!rows.next()) return false;
                    ObjectNode recorded=object(rows.getString(1));recorded.remove("metadata");
                    return recorded.equals(VisitUpdateEvent.interval(visit));
                }
            }
        });
    }
	
	public boolean hasCurrentMetadata(Visit visit) {
        return sessions.getCurrentSession().doReturningWork(c -> {
            try(PreparedStatement q=c.prepareStatement("select snapshot_json,version_json from synchronizationmr_visit_state where visit_id=?")) {
                q.setInt(1,visit.getId());try(ResultSet r=q.executeQuery()) {
                    if(!r.next() || !object(r.getString(2)).path("metadataManaged").asBoolean()) return false;
                    ObjectNode expected=(ObjectNode)object(r.getString(1)).path("metadata").deepCopy();
                    ObjectNode actual=VisitMetadata.snapshot(visit);
                    // La cascada de anulación cambia los atributos activos, sin reabrir la visita.
                    if(visit.getVoided()) { expected.remove("attributes");actual.remove("attributes"); }
                    return actual.equals(expected);
                }
            }
        });
    }
	
	private String anchor(Connection c,Visit v) throws SQLException {
        try(PreparedStatement q=c.prepareStatement("select e.encounter_uuid from synchronizationmr_encounter_event e join encounter n on n.encounter_id=e.encounter_id where n.visit_id=? order by e.event_uuid")) {
            q.setInt(1,v.getId());try(ResultSet r=q.executeQuery()) {if(r.next())return r.getString(1);}
        }
        throw new APIException("Falta el encuentro de referencia");
    }
	
	private void insert(Connection c,Visit v,VisitUpdateEvent e)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("insert into synchronizationmr_visit_update (event_uuid,encounter_id,encounter_uuid,origin_server_id,entity_sequence,date_created,payload_json) values (?,(select encounter_id from encounter where uuid=?),?,?,?,?,?)")) {
            q.setString(1,e.eventUuid);q.setString(2,e.encounterUuid);q.setString(3,e.encounterUuid);q.setString(4,e.origin);q.setLong(5,e.sequence);q.setTimestamp(6,Timestamp.from(e.occurredAt));q.setString(7,e.json);q.executeUpdate();
        }
    }
	
	private void save(Connection c,Visit v,ObjectNode snapshot,ObjectNode version,boolean exists)throws SQLException {
        try(PreparedStatement q=c.prepareStatement(exists?"update synchronizationmr_visit_state set snapshot_json=?,version_json=? where visit_id=?":"insert into synchronizationmr_visit_state (snapshot_json,version_json,visit_id) values (?,?,?)")) {
            q.setString(1,snapshot.toString());q.setString(2,version.toString());q.setInt(3,v.getId());q.executeUpdate();
        }
    }
	
	private ObjectNode object(String json) {
		try {
			return (ObjectNode) M.readTree(json);
		}
		catch (Exception ex) {
			throw new APIException("Estado de visita inválido", ex);
		}
	}
	
	private static class State {
		
		final String encounter;
		
		final ObjectNode snapshot, version;
		
		final boolean persisted;
		
		State(String e, ObjectNode s, ObjectNode v, boolean p) {
			encounter = e;
			snapshot = s;
			version = v;
			persisted = p;
		}
	}
}

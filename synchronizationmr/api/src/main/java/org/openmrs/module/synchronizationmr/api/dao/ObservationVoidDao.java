package org.openmrs.module.synchronizationmr.api.dao;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.SessionFactory;
import org.openmrs.*;
import org.openmrs.api.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.stereotype.Repository;

/** Publica anulaciones pendientes; cada valor anterior permanece en la tabla nativa. */
@Repository("synchronizationmr.ObservationVoidDao")
public class ObservationVoidDao {
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessions;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao node;
	
	private static final ObjectMapper M = new ObjectMapper();
	
	public int capture(Encounter encounter){
        if(encounter==null || encounter.getId()==null || encounter.getVoided())return 0;
        String origin=node.getLocalServerId();sessions.getCurrentSession().flush();
        return sessions.getCurrentSession().doReturningWork(c->{
            Set<String> published=new HashSet<>(), alreadyVoided=new HashSet<>(), historical=new HashSet<>();
            long time=System.currentTimeMillis();
            for(String table:new String[]{"synchronizationmr_encounter_event","synchronizationmr_encounter_addition","synchronizationmr_encounter_update","synchronizationmr_encounter_correction","synchronizationmr_encounter_void"}) {
            try(PreparedStatement q=c.prepareStatement("select payload_json from "+table+" where encounter_id=? for update")){
                q.setInt(1,encounter.getId());try(ResultSet r=q.executeQuery()){while(r.next()){
                    JsonNode event=M.readTree(r.getString(1));
                    time=Math.max(time,Math.addExact(Instant.parse(event.path("occurredAt").asText()).toEpochMilli(),1));
                    collect(event.path("payload").path("obs"),published,alreadyVoided);
                    if("VOID_OBS".equals(event.path("operation").asText()))for(JsonNode item:event.path("payload").path("observations"))alreadyVoided.add(item.path("uuid").asText());
                }}
            }catch(java.io.IOException ex){throw new APIException("Contrato de anulacion no admitido");}
            }
            // Las versiones desplazadas por CORRECT_OBS ya se transportaron como historial.
            Map<String,String> heads=new HashMap<>();
            try(PreparedStatement q=c.prepareStatement("select root_uuid,head_uuid from synchronizationmr_obs_head where encounter_id=? for update")){
                q.setInt(1,encounter.getId());try(ResultSet r=q.executeQuery()){while(r.next()){heads.put(r.getString(1),r.getString(2));if(!r.getString(1).equals(r.getString(2)))historical.add(r.getString(1));}}
            }
            try(PreparedStatement q=c.prepareStatement("select obs_uuid,root_uuid from synchronizationmr_obs_revision where encounter_id=? for update")){
                q.setInt(1,encounter.getId());try(ResultSet r=q.executeQuery()){while(r.next())if(heads.containsKey(r.getString(2))&&!r.getString(1).equals(heads.get(r.getString(2))))historical.add(r.getString(1));}
            }
            List<ObjectNode> pending=new ArrayList<>();
            try(PreparedStatement q=c.prepareStatement("select uuid,void_reason,date_voided from obs where encounter_id=? and voided=true order by obs_id for update")){
                q.setInt(1,encounter.getId());try(ResultSet r=q.executeQuery()){while(r.next()){
                    String id=r.getString(1);if(!published.contains(id)||alreadyVoided.contains(id)||historical.contains(id))continue;
                    ObjectNode item=M.createObjectNode();item.put("uuid",id);
                    String reason=r.getString(2);item.put("reason",reason==null||reason.trim().isEmpty()?"Observacion anulada en origen":reason);
                    Timestamp date=r.getTimestamp(3);item.put("dateVoided",date==null?Instant.ofEpochMilli(time).toString():date.toInstant().toString());pending.add(item);
                }}
            }
            for(int offset=0;offset<pending.size();offset+=100){
                ArrayNode items=M.createArrayNode();for(int i=offset;i<Math.min(offset+100,pending.size());i++)items.add(pending.get(i));
                long sequence;try(PreparedStatement q=c.prepareStatement("select encounter_sequence from synchronizationmr_local_node where singleton_id=1");ResultSet r=q.executeQuery()){r.next();sequence=Math.addExact(r.getLong(1),1);}
                ObservationVoidEvent event=new ObservationVoidEvent(ObservationVoidEvent.create(encounter,items,origin,sequence,Instant.ofEpochMilli(time++)));
                remember(c,encounter,event);insert(c,encounter,event);
                try(PreparedStatement q=c.prepareStatement("update synchronizationmr_local_node set encounter_sequence=? where singleton_id=1")){q.setLong(1,sequence);q.executeUpdate();}
            }
            return pending.size();
        });
    }
	
	private void collect(JsonNode items, Set<String> published, Set<String> voided) {
		for (JsonNode item : items) {
			String id = item.path("uuid").asText();
			published.add(id);
			if (item.path("voided").asBoolean())
				voided.add(id);
			collect(item.path("groupMembers"), published, voided);
		}
	}
	
	public void receive(Connection c, ObservationVoidEvent event) throws SQLException {
		if (!Context.hasPrivilege("Edit Observations"))
			throw new APIAuthenticationException("La anulacion requiere Edit Observations");
		Encounter encounter = Context.getEncounterService().getEncounterByUuid(event.encounterUuid);
		if (encounter == null)
			throw new EncounterDependencyException("Falta el encuentro de la anulacion");
		if (encounter.getPatient().getVoided() || !event.patientUuid.equals(encounter.getPatient().getUuid()))
			throw new APIException("Contrato de anulacion no admitido");
		if (!Context.getEncounterService().canEditEncounter(encounter, null))
			throw new APIAuthenticationException("Falta permiso para editar el tipo de encuentro");
		for (JsonNode item : event.observations()) {
			Obs obs = Context.getObsService().getObsByUuid(item.path("uuid").asText());
			if (obs == null)
				throw new EncounterDependencyException("Falta la observacion que se anula");
			if (obs.getEncounter() == null || !encounter.getUuid().equals(obs.getEncounter().getUuid())
			        || !event.patientUuid.equals(obs.getPerson().getUuid()))
				throw new APIException("Contrato de anulacion no admitido");
		}
		remember(c, encounter, event);
		apply(c, encounter.getId());
		insert(c, encounter, event);
		for (JsonNode item : event.observations()) {
			Obs refreshed = Context.getObsService().getObsByUuid(item.path("uuid").asText());
			sessions.getCurrentSession().refresh(refreshed);
		}
		sessions.getCurrentSession().refresh(encounter);
	}
	
	private void remember(Connection c,Encounter encounter,ObservationVoidEvent event)throws SQLException{
        for(JsonNode item:event.observations()){
            String id=item.path("uuid").asText(), prior=null;
            try(PreparedStatement q=c.prepareStatement("select version_json from synchronizationmr_obs_void where obs_uuid=? for update")){q.setString(1,id);try(ResultSet r=q.executeQuery()){if(r.next())prior=r.getString(1);}}
            try{if(prior!=null&&PatientUpdateEvent.compare(event.version(),M.readTree(prior))<=0)continue;}catch(java.io.IOException ex){throw new APIException("Contrato de anulacion no admitido");}
            String sql=prior==null?"insert into synchronizationmr_obs_void (reason,date_voided,version_json,encounter_id,obs_uuid) values (?,?,?,?,?)":"update synchronizationmr_obs_void set reason=?,date_voided=?,version_json=?,encounter_id=? where obs_uuid=?";
            try(PreparedStatement q=c.prepareStatement(sql)){q.setString(1,item.path("reason").asText());q.setTimestamp(2,Timestamp.from(Instant.parse(item.path("dateVoided").asText())));q.setString(3,event.version().toString());q.setInt(4,encounter.getId());q.setString(5,id);q.executeUpdate();}
        }
    }
	
	/** Una reconciliacion de versiones no debe reactivar una anulacion explicita. */
	public static void apply(Connection c,int encounterId)throws SQLException{
        try(PreparedStatement q=c.prepareStatement("select obs_uuid,reason,date_voided from synchronizationmr_obs_void where encounter_id=?")){
            q.setInt(1,encounterId);try(ResultSet r=q.executeQuery()){while(r.next()){
                try(PreparedStatement u=c.prepareStatement("update obs set voided=true,void_reason=?,date_voided=?,voided_by=? where uuid=? and encounter_id=?")){
                    u.setString(1,r.getString(2));u.setTimestamp(2,r.getTimestamp(3));u.setInt(3,Context.getAuthenticatedUser().getId());u.setString(4,r.getString(1));u.setInt(5,encounterId);u.executeUpdate();
                }
            }}
        }
    }
	
	private void insert(Connection c,Encounter encounter,ObservationVoidEvent event)throws SQLException{
        try(PreparedStatement q=c.prepareStatement("insert into synchronizationmr_encounter_void (event_uuid,encounter_id,encounter_uuid,origin_server_id,entity_sequence,date_created,payload_json) values (?,?,?,?,?,?,?)")){
            q.setString(1,event.eventUuid);q.setInt(2,encounter.getId());q.setString(3,event.encounterUuid);q.setString(4,event.origin);q.setLong(5,event.sequence);q.setTimestamp(6,Timestamp.from(event.occurredAt));q.setString(7,event.json);q.executeUpdate();
        }
    }
}

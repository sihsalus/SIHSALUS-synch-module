package org.openmrs.module.synchronizationmr.api.dao;

import java.sql.*;
import java.time.Instant;
import java.util.Date;
import org.hibernate.SessionFactory;
import org.openmrs.Encounter;
import org.openmrs.api.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.stereotype.Repository;

/** Conserva el evento de anulacion en la misma transaccion que el cambio clinico. */
@Repository("synchronizationmr.EncounterVoidDao")
public class EncounterVoidDao {
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessions;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao node;
	
	public void capture(Encounter encounter) {
        if (encounter == null || encounter.getId() == null || !encounter.getVoided()) return;
        String origin = node.getLocalServerId();
        sessions.getCurrentSession().flush();
        sessions.getCurrentSession().doWork(c -> {
            // Un registro que nunca se publico se anula solo en su base de origen.
            try (PreparedStatement q = c.prepareStatement("select payload_json from synchronizationmr_encounter_event where encounter_id=?")) {
                q.setInt(1,encounter.getId());
                try (ResultSet r=q.executeQuery()) { if (!r.next() || r.getString(1)==null || r.getString(1).trim().isEmpty()) return; }
            }
            try (PreparedStatement q = c.prepareStatement("select event_uuid from synchronizationmr_encounter_annulment where encounter_id=?")) {
                q.setInt(1,encounter.getId());
                try (ResultSet r=q.executeQuery()) { if (r.next()) return; }
            }
            long sequence;
            try (PreparedStatement q=c.prepareStatement("select encounter_sequence from synchronizationmr_local_node where singleton_id=1"); ResultSet r=q.executeQuery()) {
                if (!r.next()) throw new APIException("Falta la identidad local");
                sequence=Math.addExact(r.getLong(1),1);
            }
            EncounterVoidEvent event=new EncounterVoidEvent(EncounterVoidEvent.create(encounter,origin,sequence,Instant.ofEpochMilli(System.currentTimeMillis())));
            insert(c,encounter,event);
            try (PreparedStatement q=c.prepareStatement("update synchronizationmr_local_node set encounter_sequence=? where singleton_id=1")) {
                q.setLong(1,sequence);q.executeUpdate();
            }
        });
    }
	
	public void receive(Connection c, EncounterVoidEvent event) throws SQLException {
        if (!Context.hasPrivilege("Delete Encounters")) throw new APIAuthenticationException("La anulacion requiere Delete Encounters");
        Encounter encounter=Context.getEncounterService().getEncounterByUuid(event.encounterUuid);
        if (encounter==null) throw new EncounterDependencyException("Falta el encuentro que se anula");
        if (!event.patientUuid.equals(encounter.getPatient().getUuid())) throw new APIException("El paciente no corresponde al encuentro");
        if (!Context.getEncounterService().canEditEncounter(encounter,null)) throw new APIAuthenticationException("Falta permiso para editar el tipo de encuentro");
        if (!encounter.getVoided()) {
            // La API nativa conserva las observaciones y anula tambien las ordenes asociadas.
            // El contexto de recepcion impide crear eventos locales por esa cascada.
            encounter.setDateVoided(Date.from(event.dateVoided));
            IncomingEncounterSave.save(encounter, () -> Context.getEncounterService().voidEncounter(encounter,event.reason));
            sessions.getCurrentSession().flush();
        }
        insert(c,encounter,event);
    }
	
	/** La anulacion prevalece; los valores recibidos despues permanecen como historial. */
	public void preserveAnnulment(Connection c,Encounter encounter) throws SQLException {
        if(encounter==null || !encounter.getVoided()) return;
        sessions.getCurrentSession().flush();
        try(PreparedStatement q=c.prepareStatement("update obs set voided=true,void_reason=?,date_voided=?,voided_by=? where encounter_id=? and voided=false")) {
            q.setString(1,encounter.getVoidReason()==null?"Encuentro anulado":encounter.getVoidReason());
            q.setTimestamp(2,new Timestamp(encounter.getDateVoided().getTime()));
            q.setInt(3,Context.getAuthenticatedUser().getId());q.setInt(4,encounter.getId());q.executeUpdate();
        }
        try(PreparedStatement q=c.prepareStatement("select obs_id from obs where encounter_id=?")) {
            q.setInt(1,encounter.getId());try(ResultSet r=q.executeQuery()) { while(r.next()) {
                org.openmrs.Obs obs=Context.getObsService().getObs(r.getInt(1));
                sessions.getCurrentSession().refresh(obs);
            }}
        }
        sessions.getCurrentSession().refresh(encounter);
    }
	
	private void insert(Connection c, Encounter encounter, EncounterVoidEvent event) throws SQLException {
        try (PreparedStatement q=c.prepareStatement("insert into synchronizationmr_encounter_annulment (event_uuid,encounter_id,encounter_uuid,origin_server_id,entity_sequence,date_created,payload_json) values (?,?,?,?,?,?,?)")) {
            q.setString(1,event.eventUuid);q.setInt(2,encounter.getId());q.setString(3,event.encounterUuid);
            q.setString(4,event.origin);q.setLong(5,event.sequence);q.setTimestamp(6,Timestamp.from(event.occurredAt));
            q.setString(7,event.json);q.executeUpdate();
        }
    }
}

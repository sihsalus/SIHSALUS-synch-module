package org.openmrs.module.synchronizationmr.api.dao;

import java.sql.*;
import org.hibernate.SessionFactory;
import org.openmrs.Encounter;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.stereotype.Repository;

/** Conserva el complemento por separado; ninguna preparación reescribe un CREATE. */
@Repository("synchronizationmr.EncounterVisitSupplementDao")
public class EncounterVisitSupplementDao {
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessions;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao node;
	
	public boolean prepare(String encounterUuid) {
        String local=node.getLocalServerId();
        return sessions.getCurrentSession().doReturningWork(c -> {
            String original;
            try(PreparedStatement q=c.prepareStatement("select payload_json from synchronizationmr_encounter_event where encounter_uuid=? and origin_server_id=? for update")) {
                q.setString(1,encounterUuid);q.setString(2,local);
                try(ResultSet r=q.executeQuery()){if(!r.next())throw new APIException("Solo se prepara un CREATE propio existente");original=r.getString(1);}
            }
            EncounterIncomingEvent event=new EncounterIncomingEvent(original);
            if(!wire(c,event.eventUuid,original).equals(original))return false;
            Encounter encounter=Context.getEncounterService().getEncounterByUuid(encounterUuid);
            if(encounter==null || encounter.getVoided() || encounter.getVisit()==null || encounter.getVisit().getVoided()
                || !event.patientUuid.equals(encounter.getPatient().getUuid()))throw new APIException("La visita no puede prepararse");
            String wire=EncounterVisitSupplement.prepare(original,encounter.getVisit());
            record(c,new EncounterIncomingEvent(wire));
            return true;
        });
    }
	
	public static String wire(Connection c,String eventUuid,String original)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("select wire_json from synchronizationmr_visit_supplement where event_uuid=?")) {
            q.setString(1,eventUuid);try(ResultSet r=q.executeQuery()){return r.next()?r.getString(1):original;}
        }
    }
	
	public static void record(Connection c,EncounterIncomingEvent event)throws SQLException {
        if(event.wireJson.equals(event.json))return;
        try(PreparedStatement q=c.prepareStatement("insert into synchronizationmr_visit_supplement (event_uuid,wire_json) values (?,?)")) {
            q.setString(1,event.eventUuid);q.setString(2,event.wireJson);q.executeUpdate();
        }
    }
}

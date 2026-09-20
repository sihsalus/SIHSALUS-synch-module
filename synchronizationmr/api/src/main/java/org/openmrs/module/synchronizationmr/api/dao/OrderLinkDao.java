package org.openmrs.module.synchronizationmr.api.dao;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.util.*;
import org.hibernate.SessionFactory;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.EncounterIncomingEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Persistent dependencies avoid the encounter -> order -> encounter cycle. Same transaction as
 * receive.
 */
@Repository("synchronizationmr.OrderLinkDao")
public class OrderLinkDao {
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessionFactory;
	
	public void record(EncounterIncomingEvent event) {
		JsonNode payload = event.root.get("payload");
		for (JsonNode id : payload.path("orderUuids")) {
			if (!id.isTextual())
				throw new APIException("Invalid order reference");
			add(id.textValue(), event.encounterUuid, event.patientUuid, null);
		}
		for (JsonNode obs : payload.path("obs"))
			recordObs(obs, event);
	}
	
	private void recordObs(JsonNode obs, EncounterIncomingEvent event) {
		JsonNode id = obs.get("orderUuid");
		if (id != null && !id.isNull()) {
			if (!id.isTextual())
				throw new APIException("Invalid order reference");
			add(id.textValue(), event.encounterUuid, event.patientUuid, obs.path("uuid").asText());
		}
		for (JsonNode child : obs.path("groupMembers"))
			recordObs(child, event);
	}
	
	private void add(String order,String encounter,String patient,String obs) {
        if(order==null || order.trim().isEmpty() || order.length()>38)throw new APIException("Invalid order UUID");
        sessionFactory.getCurrentSession().doWork(c -> {
            try(PreparedStatement s=c.prepareStatement("insert into synchronizationmr_order_link (link_uuid,order_uuid,encounter_uuid,patient_uuid,obs_uuid) values (?,?,?,?,?)")) {
                s.setString(1,UUID.randomUUID().toString());s.setString(2,order);s.setString(3,encounter);s.setString(4,patient);s.setString(5,obs);s.executeUpdate();
            }
        });
        Order existing=Context.getOrderService().getOrderByUuid(order);
        if(existing!=null)resolve(existing);
    }
	
	public void resolve(Order order) {
        List<String[]> links=sessionFactory.getCurrentSession().doReturningWork(c -> {
            List<String[]> result=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement("select link_uuid,encounter_uuid,patient_uuid,obs_uuid from synchronizationmr_order_link where order_uuid = ?")) {
                s.setString(1,order.getUuid());try(ResultSet r=s.executeQuery()){while(r.next())result.add(new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)});}
            }return result;
        });
        for(String[] link:links) {
            if(!link[2].equals(order.getPatient().getUuid()) || Boolean.TRUE.equals(order.getVoided())) throw new APIException("Order link patient mismatch or voided order");
            if(link[3]==null) {
                if(order.getEncounter()==null || !link[1].equals(order.getEncounter().getUuid()))throw new APIException("Order belongs to another encounter");
            } else {
                Obs obs=Context.getObsService().getObsByUuid(link[3]);
                if(obs==null || !link[2].equals(obs.getPerson().getUuid()) || obs.getEncounter()==null
                    || !link[1].equals(obs.getEncounter().getUuid())
                    || (obs.getOrder()!=null && !order.getUuid().equals(obs.getOrder().getUuid())))throw new APIException("Observation order link mismatch");
                // Complete an imported foreign key, not a clinical edit/revision of the observation.
                sessionFactory.getCurrentSession().doWork(c -> {
                    try (PreparedStatement s=c.prepareStatement("update obs set order_id = ? where obs_id = ? and (order_id is null or order_id = ?)")) {
                        s.setInt(1,order.getOrderId()); s.setInt(2,obs.getObsId()); s.setInt(3,order.getOrderId());
                        if(s.executeUpdate()!=1) throw new APIException("Concurrent observation order change");
                    }
                });
                sessionFactory.getCurrentSession().refresh(obs);
            }
            sessionFactory.getCurrentSession().doWork(c -> {
                try(PreparedStatement s=c.prepareStatement("delete from synchronizationmr_order_link where link_uuid = ?")) {s.setString(1,link[0]);s.executeUpdate();}
            });
        }
        sessionFactory.getCurrentSession().flush();
    }
	
	public long countPending() {
        return sessionFactory.getCurrentSession().doReturningWork(c -> {
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("select count(*) from synchronizationmr_order_link")) {r.next();return r.getLong(1);}
        });
    }
}

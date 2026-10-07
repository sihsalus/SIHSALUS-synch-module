package org.openmrs.module.synchronizationmr.api.dao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import org.hibernate.SessionFactory;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.springframework.stereotype.Repository;

/** Reconoce reemplazos uno a uno dentro de un mismo saveEncounter, nunca por proximidad temporal. */
@Repository("synchronizationmr.ObservationReplacementDao")
public class ObservationReplacementDao {

	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessions;

	private static final ObjectMapper M = new ObjectMapper();

	public Map<String,String> before(Encounter encounter) {
        if(encounter == null || encounter.getId() == null || encounter.getVoided()) return Collections.emptyMap();
        // JDBC no provoca el autoflush de objetos que REST ya modifico en memoria.
        return sessions.getCurrentSession().doReturningWork(c -> {
            Set<String> published = new HashSet<>();
            for(String table:new String[]{"synchronizationmr_encounter_event","synchronizationmr_encounter_addition","synchronizationmr_encounter_correction"}) {
            try(PreparedStatement q=c.prepareStatement("select payload_json from " + table + " where encounter_id=? for update")) {
                q.setInt(1,encounter.getId());
                try(ResultSet r=q.executeQuery()) { while(r.next()) {
                    try { collect(M.readTree(r.getString(1)).path("payload").path("obs"),published); }
                    catch(java.io.IOException ex) { throw new APIException("No se pudo leer el historial de observaciones",ex); }
                }}
            }
            }
            Map<String,String> rows=read(c,encounter.getId());
            // Conserva todos los UUID previos para no confundir una observacion vieja con una nueva.
            for(String id:rows.keySet()) if(!published.contains(id)) rows.put(id,null);
            return rows;
        });
    }

	public void link(Encounter encounter, Map<String,String> before) {
        if(before==null || before.isEmpty() || encounter.getVoided()) return;
        sessions.getCurrentSession().flush();
        Map<String,String> after=sessions.getCurrentSession().doReturningWork(c -> read(c,encounter.getId()));
        Map<String,List<String>> oldByKey=group(before), newByKey=group(after);
        for(Map.Entry<String,List<String>> entry:oldByKey.entrySet()) {
            List<String> old=entry.getValue(), current=newByKey.get(entry.getKey());
            // Si existen varias mediciones del mismo contexto, no se adivina cual reemplaza a cual.
            if(old.size()!=1 || current==null || current.size()!=1) continue;
            String previous=old.get(0), next=current.get(0);
            if(!after.containsKey(previous) || after.get(previous)!=null || before.containsKey(next)) continue;
            Obs prior=Context.getObsService().getObsByUuid(previous), replacement=Context.getObsService().getObsByUuid(next);
            if(prior==null || replacement==null || !prior.getVoided() || replacement.getPreviousVersion()!=null
                || replacement.getConcept().isComplex() || replacement.isObsGrouping()) continue;
            // El valor clinico es inmutable. Se incorpora solo el enlace de historial,
            // igual que al reconciliar versiones recibidas, dentro de la transaccion del guardado.
            sessions.getCurrentSession().doWork(c -> {
                try(PreparedStatement q=c.prepareStatement("select obs_id from obs where previous_version=?")) {
                    q.setInt(1,prior.getId());try(ResultSet r=q.executeQuery()) { if(r.next()) return; }
                }
                try(PreparedStatement q=c.prepareStatement("update obs set previous_version=? where obs_id=? and previous_version is null")) {
                    q.setInt(1,prior.getId());q.setInt(2,replacement.getId());q.executeUpdate();
                }
            });
            sessions.getCurrentSession().refresh(replacement);
        }
        // CORRECT_OBS captura el enlace y mantiene la misma regla de versionado que una correccion nativa.
        sessions.getCurrentSession().flush();
    }

	private Map<String,String> read(Connection c,int encounterId) throws SQLException {
        Map<String,String> rows=new LinkedHashMap<>();
        try(PreparedStatement q=c.prepareStatement("select o.uuid,o.voided,o.person_id,o.concept_id,o.obs_datetime,o.location_id,o.order_id,o.obs_group_id,o.form_namespace_and_path,"
            + "(select count(*) from obs child where child.obs_group_id=o.obs_id) from obs o where o.encounter_id=? order by o.obs_id for update")) {
            q.setInt(1,encounterId);
            try(ResultSet r=q.executeQuery()) { while(r.next()) {
                List<String> fields=new ArrayList<>();
                for(int i=3;i<=9;i++)fields.add(r.getString(i));
                String key;
                try { key=M.writeValueAsString(fields); } catch(java.io.IOException ex) { throw new APIException("Contexto de observacion invalido",ex); }
                rows.put(r.getString(1),r.getBoolean(2)||r.getInt(10)>0?null:key);
            }}
        }
        return rows;
    }

	private Map<String,List<String>> group(Map<String,String> rows) {
        Map<String,List<String>> result=new HashMap<>();
        for(Map.Entry<String,String> row:rows.entrySet()) if(row.getValue()!=null)
            result.computeIfAbsent(row.getValue(), k -> new ArrayList<>()).add(row.getKey());
        return result;
    }

	private void collect(JsonNode items, Set<String> ids) {
		for (JsonNode item : items) {
			ids.add(item.path("uuid").asText());
			collect(item.path("groupMembers"), ids);
		}
	}
}

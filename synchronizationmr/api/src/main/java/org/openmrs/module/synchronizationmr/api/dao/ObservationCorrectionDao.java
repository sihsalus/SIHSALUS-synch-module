package org.openmrs.module.synchronizationmr.api.dao;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.SessionFactory;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.stereotype.Repository;

/** Versiones inmutables y elecciÃ³n de la versiÃ³n vigente por observaciÃ³n original. */
@Repository("synchronizationmr.ObservationCorrectionDao")
public class ObservationCorrectionDao {

	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessions;

	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao node;

	@javax.annotation.Resource(name = "synchronizationmr.OrderLinkDao")
	private OrderLinkDao orderLinks;

	private static final ObjectMapper M = new ObjectMapper();

	private final EncounterCreationPayloadSerializer serializer = new EncounterCreationPayloadSerializer();

	public void capture(Encounter encounter) {
        if (encounter == null || encounter.getEncounterId() == null || encounter.getVoided()) return;
        String origin = node.getLocalServerId(); sessions.getCurrentSession().flush();
        sessions.getCurrentSession().doWork(c -> {
            Set<String> known = new HashSet<>(); boolean creation = false;
            long time = System.currentTimeMillis();
            // Las lecturas actuales evitan una instantanea anterior al bloqueo en MariaDB.
            for (String table : new String[] {"synchronizationmr_encounter_event", "synchronizationmr_encounter_addition",
                    "synchronizationmr_encounter_update", "synchronizationmr_encounter_correction"}) {
            try (PreparedStatement q = c.prepareStatement("select payload_json from " + table + " where encounter_id=? for update")) {
                q.setInt(1, encounter.getEncounterId());
                try (ResultSet r = q.executeQuery()) {
                    while (r.next()) {
                        JsonNode event = parse(r.getString(1));
                        creation |= "CREATE".equals(event.path("operation").asText());
                        collect(event.path("payload").path("obs"), known);
                        time = Math.max(time, Math.addExact(Instant.parse(event.path("occurredAt").asText()).toEpochMilli(), 1));
                    }
                }
            }
            }
            if (!creation) return;
            Map<String, Obs> all = observations(c, encounter);
            Map<String, Obs> added = new LinkedHashMap<>();
            for (Obs obs : all.values()) {
                if (!known.contains(obs.getUuid()) && obs.getPreviousVersion() != null
                    && known.contains(lineage(c, obs, encounter))) added.put(obs.getUuid(), obs);
            }
            if (added.isEmpty()) return;
            // Incluye miembros nuevos que nacieron dentro de un grupo corregido en el mismo guardado.
            boolean more;
            do {
                more = false;
                for (Obs obs : all.values()) {
                    if (!known.contains(obs.getUuid()) && !added.containsKey(obs.getUuid()) && obs.getObsGroup() != null
                        && added.containsKey(obs.getObsGroup().getUuid())) {
                        added.put(obs.getUuid(), obs); more = true;
                    }
                }
            } while (more);
            ArrayNode values = M.createArrayNode(); Map<String, String> heads = new LinkedHashMap<>();
            for (Obs obs : added.values()) {
                String root = lineage(c, obs, encounter);
                ObjectNode value = serializer.observationVersion(obs); value.put("rootUuid", root); values.add(value);
                Obs selected = all.get(heads.get(root));
                if (selected == null || !obs.getVoided() || selected.getVoided()) heads.put(root, obs.getUuid());
            }
            long seq;
            try (PreparedStatement q = c.prepareStatement("select encounter_sequence from synchronizationmr_local_node where singleton_id=1"); ResultSet r = q.executeQuery()) {
                if (!r.next()) throw new APIException("Falta el nodo local"); seq = Math.addExact(r.getLong(1), 1);
            }
            ObservationCorrectionEvent event = new ObservationCorrectionEvent(ObservationCorrectionEvent.create(encounter, values, heads, origin, seq, Instant.ofEpochMilli(time)));
            remember(c, encounter, event);
            for (Map.Entry<String, String> head : event.heads.entrySet()) setHead(c, encounter, head.getKey(), head.getValue(), event.version());
            insert(c, encounter, event);
            try (PreparedStatement q = c.prepareStatement("update synchronizationmr_local_node set encounter_sequence=? where singleton_id=1")) {
                q.setLong(1, seq); q.executeUpdate();
            }
        });
    }

	public void receive(Connection c, ObservationCorrectionEvent event) throws SQLException {
        if (!Context.hasPrivilege("Edit Observations")) throw new org.openmrs.api.APIAuthenticationException("Las correcciones requieren Edit Observations");
        Encounter encounter = Context.getEncounterService().getEncounterByUuid(event.encounterUuid);
        if (encounter == null) throw new EncounterDependencyException("Falta el encuentro de la correcciÃ³n");
        if (encounter.getPatient().getVoided() || !event.patientUuid.equals(encounter.getPatient().getUuid())) throw invalid();
        if (!Context.getEncounterService().canEditEncounter(encounter, null)) throw new org.openmrs.api.APIAuthenticationException("Falta permiso para editar el tipo de encuentro");
        Map<String, Obs> added = new LinkedHashMap<>();
        for (Obs obs : event.decode(encounter)) added.put(obs.getUuid(), obs);
        Map<String, JsonNode> data = new HashMap<>();
        for (JsonNode item : event.observations()) data.put(item.path("uuid").asText(), item);
        // Comprueba referencias, pertenencia y ciclos antes de persistir ninguna fila.
        for (Obs obs : added.values()) {
            JsonNode item = data.get(obs.getUuid());
            String previous = nullable(item, "previousVersionUuid"), parent = nullable(item, "parentGroupUuid");
            String root = rootForIncoming(c, obs.getUuid(), data, encounter, new HashSet<>());
            if (!root.equals(item.path("rootUuid").asText())) throw invalid();
            if (previous == null && (parent == null || !added.containsKey(parent))) throw invalid();
            if (parent != null) {
                Obs group = added.containsKey(parent) ? added.get(parent) : requireObs(parent, encounter);
                if (!Boolean.TRUE.equals(group.getConcept().getSet())) throw invalid();
                obs.setObsGroup(group);
                if (added.containsKey(parent)) group.addGroupMember(obs);
            }
            Set<String> seen = new HashSet<>(); Obs cursor = obs;
            while (cursor != null) {
                if (!seen.add(cursor.getUuid()) || seen.size() > 1000) throw invalid();
                cursor = cursor.getObsGroup();
            }
            if (previous != null) {
                Obs prior = added.containsKey(previous) ? added.get(previous) : requireObs(previous, encounter);
                String oldOrder = data.containsKey(previous) ? nullable(data.get(previous), "orderUuid")
                    : prior.getOrder() == null ? pendingOrder(c, prior.getUuid()) : prior.getOrder().getUuid();
                if (!Objects.equals(oldOrder, nullable(item, "orderUuid"))) throw new APIException("Una correcciÃ³n no puede cambiar la orden del resultado");
            }
        }
        for (Obs obs : added.values()) org.openmrs.validator.ValidateUtil.validate(obs);
        // Inserta versiones con UUID de origen; no invoca saveObs sobre UUID existentes.
        Set<String> saved = new HashSet<>();
        for (Obs obs : added.values()) saveNew(obs, added, saved);
        sessions.getCurrentSession().flush();
        remember(c, encounter, event);
        for (Map.Entry<String, String> entry : event.heads.entrySet()) {
            JsonNode previous = headVersion(c, entry.getKey());
            if (previous == null || PatientUpdateEvent.compare(event.version(), previous) > 0) setHead(c, encounter, entry.getKey(), entry.getValue(), event.version());
        }
        reconcile(c, encounter);
        orderLinks.record(new EncounterIncomingEvent(event.json));
        insert(c, encounter, event);
    }

	private void saveNew(Obs obs, Map<String, Obs> added, Set<String> saved) {
		if (saved.contains(obs.getUuid()))
			return;
		Obs parent = obs.getObsGroup();
		if (parent != null && added.containsKey(parent.getUuid()))
			saveNew(parent, added, saved);
		obs.setCreator(Context.getAuthenticatedUser());
		obs.setDateCreated(new java.util.Date());
		if (obs.getVoided()) {
			obs.setVoidedBy(Context.getAuthenticatedUser());
			obs.setDateVoided(new java.util.Date());
		}
		sessions.getCurrentSession().save(obs);
		saved.add(obs.getUuid());
	}

	private String rootForIncoming(Connection c, String id, Map<String, JsonNode> incoming, Encounter encounter,
	        Set<String> seen) throws SQLException {
		if (!seen.add(id) || seen.size() > 1000)
			throw invalid();
		JsonNode value = incoming.get(id);
		if (value == null)
			return lineage(c, requireObs(id, encounter), encounter);
		String previous = nullable(value, "previousVersionUuid");
		return previous == null ? id : rootForIncoming(c, previous, incoming, encounter, seen);
	}

	private String lineage(Connection c, Obs obs, Encounter encounter) throws SQLException {
        Set<String> seen = new HashSet<>();
        while (obs != null) {
            own(obs, encounter);
            if (!seen.add(obs.getUuid()) || seen.size() > 1000) throw invalid();
            try (PreparedStatement q = c.prepareStatement("select root_uuid from synchronizationmr_obs_revision where obs_uuid=? for update")) {
                q.setString(1, obs.getUuid()); try (ResultSet r = q.executeQuery()) { if (r.next()) return r.getString(1); }
            }
            if (obs.getPreviousVersion() == null) return obs.getUuid();
            obs = obs.getPreviousVersion();
        }
        throw invalid();
    }

	private void remember(Connection c, Encounter encounter, ObservationCorrectionEvent event) throws SQLException {
        for (JsonNode item : event.observations()) {
            try (PreparedStatement q = c.prepareStatement("insert into synchronizationmr_obs_revision (obs_uuid,encounter_id,root_uuid,previous_uuid,parent_uuid,snapshot_json) values (?,?,?,?,?,?)")) {
                q.setString(1, item.path("uuid").asText()); q.setInt(2, encounter.getEncounterId()); q.setString(3, item.path("rootUuid").asText());
                q.setString(4, nullable(item,"previousVersionUuid")); q.setString(5, nullable(item,"parentGroupUuid")); q.setString(6,item.toString()); q.executeUpdate();
            }
        }
    }

	private JsonNode headVersion(Connection c, String root) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("select version_json from synchronizationmr_obs_head where root_uuid=? for update")) {
            q.setString(1,root); try (ResultSet r=q.executeQuery()) { return r.next()?parse(r.getString(1)):null; }
        }
    }

	private void setHead(Connection c, Encounter encounter, String root, String head, JsonNode version) throws SQLException {
        boolean exists = headVersion(c,root)!=null;
        try (PreparedStatement q = c.prepareStatement(exists ? "update synchronizationmr_obs_head set encounter_id=?,head_uuid=?,version_json=? where root_uuid=?"
            : "insert into synchronizationmr_obs_head (encounter_id,head_uuid,version_json,root_uuid) values (?,?,?,?)")) {
            q.setInt(1,encounter.getEncounterId()); q.setString(2,head); q.setString(3,version.toString()); q.setString(4,root); q.executeUpdate();
        }
    }

	/**
	 * La FK nativa permite un sucesor por versiÃ³n. Las ramas alternativas se conservan en el
	 * mÃ³dulo.
	 */
	private void reconcile(Connection c, Encounter encounter) throws SQLException {
        Map<String, Obs> all=observations(c,encounter);
        Map<String,String> roots=new HashMap<>(), previous=new HashMap<>(), parents=new HashMap<>(), heads=new HashMap<>();
        Map<String,Boolean> originallyVoided=new HashMap<>();
        Map<String,String> previousReasons=new HashMap<>();
        for(Obs obs:all.values()) {
            roots.put(obs.getUuid(),lineage(c,obs,encounter));
            previous.put(obs.getUuid(),obs.getPreviousVersion()==null?null:obs.getPreviousVersion().getUuid());
            parents.put(obs.getUuid(),obs.getObsGroup()==null?null:obs.getObsGroup().getUuid());
        }
        try(PreparedStatement q=c.prepareStatement("select obs_uuid,previous_uuid,parent_uuid,snapshot_json from synchronizationmr_obs_revision where encounter_id=? for update")) {
            q.setInt(1,encounter.getEncounterId()); try(ResultSet r=q.executeQuery()){while(r.next()){
                previous.put(r.getString(1),r.getString(2)); parents.put(r.getString(1),r.getString(3)); originallyVoided.put(r.getString(1),parse(r.getString(4)).path("voided").asBoolean());
                previousReasons.put(r.getString(1),nullable(parse(r.getString(4)),"previousVoidReason"));
            }}
        }
        try(PreparedStatement q=c.prepareStatement("select root_uuid,head_uuid from synchronizationmr_obs_head where encounter_id=? for update")) {
            q.setInt(1,encounter.getEncounterId()); try(ResultSet r=q.executeQuery()){while(r.next())heads.put(r.getString(1),r.getString(2));}
        }
        // Guarda solo la cadena ganadora en previous_version; nunca altera valores clÃ­nicos histÃ³ricos.
        for(Obs obs:all.values()) if(heads.containsKey(roots.get(obs.getUuid()))) {
            boolean voided=!obs.getUuid().equals(heads.get(roots.get(obs.getUuid()))) || Boolean.TRUE.equals(originallyVoided.get(obs.getUuid()));
            try(PreparedStatement q=c.prepareStatement("update obs set previous_version=null,voided=?,void_reason=?,voided_by=?,date_voided=? where obs_id=?")) {
                q.setBoolean(1,voided); q.setString(2,voided ? obs.getVoidReason() != null ? obs.getVoidReason() : "VersiÃ³n anterior o alternativa conservada por sincronizaciÃ³n" : null);
                if(voided){q.setInt(3,obs.getVoided() && obs.getVoidedBy()!=null ? obs.getVoidedBy().getUserId() : Context.getAuthenticatedUser().getUserId());q.setTimestamp(4,new Timestamp(obs.getVoided() && obs.getDateVoided()!=null ? obs.getDateVoided().getTime() : System.currentTimeMillis()));}
                else {q.setNull(3,Types.INTEGER);q.setNull(4,Types.TIMESTAMP);} q.setInt(5,obs.getObsId());q.executeUpdate();
            }
        }
        for(String head:heads.values()) {
            Set<String> seen=new HashSet<>(); String cursor=head;
            while(previous.get(cursor)!=null) {
                if(!seen.add(cursor)||seen.size()>1000)throw invalid();
                Obs obs=all.get(cursor),prior=all.get(previous.get(cursor));if(obs==null||prior==null)throw invalid();
                try(PreparedStatement q=c.prepareStatement("update obs set previous_version=? where obs_id=?")){q.setInt(1,prior.getObsId());q.setInt(2,obs.getObsId());q.executeUpdate();}
                if(previousReasons.get(cursor)!=null) {
                    try(PreparedStatement q=c.prepareStatement("update obs set void_reason=? where obs_id=?")){q.setString(1,previousReasons.get(cursor));q.setInt(2,prior.getObsId());q.executeUpdate();}
                }
                cursor=prior.getUuid();
            }
        }
        // Un miembro vigente sigue al grupo vigente, incluso si ambos llegaron desde ramas distintas.
        for(Obs obs:all.values()) {
            String root = roots.get(obs.getUuid());
            boolean active = heads.containsKey(root) ? obs.getUuid().equals(heads.get(root)) && !Boolean.TRUE.equals(originallyVoided.get(obs.getUuid())) : !obs.getVoided();
            if (!active) continue;
            String parent=parents.get(obs.getUuid()); if(parent==null)continue;
            String desired=heads.getOrDefault(roots.get(parent),parent);
            if(all.get(desired)==null)throw invalid();
            try(PreparedStatement q=c.prepareStatement("update obs set obs_group_id=? where obs_id=?")){q.setInt(1,all.get(desired).getObsId());q.setInt(2,obs.getObsId());q.executeUpdate();}
        }
        ObservationVoidDao.apply(c,encounter.getId());
        for(Obs obs:all.values()) sessions.getCurrentSession().refresh(obs);
        sessions.getCurrentSession().refresh(encounter);
    }

	private Map<String, Obs> observations(Connection c,Encounter encounter)throws SQLException {
        Map<String,Obs> result=new LinkedHashMap<>();
        try(PreparedStatement q=c.prepareStatement("select obs_id from obs where encounter_id=? order by obs_id for update")) {
            q.setInt(1,encounter.getEncounterId());try(ResultSet r=q.executeQuery()){while(r.next()){Obs obs=Context.getObsService().getObs(r.getInt(1));result.put(obs.getUuid(),obs);}}
        } return result;
    }

	private String pendingOrder(Connection c,String uuid)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("select order_uuid from synchronizationmr_order_link where obs_uuid=?")) {
            q.setString(1,uuid);try(ResultSet r=q.executeQuery()){return r.next()?r.getString(1):null;}
        }
    }

	private Obs requireObs(String uuid, Encounter encounter) {
		Obs obs = Context.getObsService().getObsByUuid(uuid);
		if (obs == null)
			throw new EncounterDependencyException("Falta la versiÃ³n previa o el grupo de la observaciÃ³n");
		own(obs, encounter);
		return obs;
	}

	private void own(Obs obs, Encounter encounter) {
		if (obs.getEncounter() == null || !encounter.getUuid().equals(obs.getEncounter().getUuid())
		        || !encounter.getPatient().getUuid().equals(obs.getPerson().getUuid()))
			throw invalid();
	}

	private void collect(JsonNode items, Set<String> ids) {
		for (JsonNode item : items) {
			ids.add(item.path("uuid").asText());
			collect(item.path("groupMembers"), ids);
		}
	}

	private JsonNode parse(String json) {
		try {
			return M.readTree(json);
		}
		catch (Exception e) {
			throw invalid();
		}
	}

	private String nullable(JsonNode item, String key) {
		return item.path(key).isMissingNode() || item.path(key).isNull() ? null : item.path(key).asText();
	}

	private APIException invalid() {
		return new APIException("La correcciÃ³n contiene referencias incompatibles o una historia invÃ¡lida");
	}

	private void insert(Connection c,Encounter encounter,ObservationCorrectionEvent event)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("insert into synchronizationmr_encounter_correction (event_uuid,encounter_id,encounter_uuid,origin_server_id,entity_sequence,date_created,payload_json) values (?,?,?,?,?,?,?)")) {
            q.setString(1,event.eventUuid);q.setInt(2,encounter.getEncounterId());q.setString(3,encounter.getUuid());q.setString(4,event.origin);q.setLong(5,event.sequence);q.setTimestamp(6,Timestamp.from(event.occurredAt));q.setString(7,event.json);q.executeUpdate();
        }
    }
}

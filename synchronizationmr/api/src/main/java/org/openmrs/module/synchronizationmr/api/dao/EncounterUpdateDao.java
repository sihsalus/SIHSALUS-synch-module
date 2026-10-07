package org.openmrs.module.synchronizationmr.api.dao;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.SessionFactory;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.stereotype.Repository;

/** Estado de metadatos y eventos inmutables dentro de la misma transacciÃ³n clÃ­nica. */
@Repository("synchronizationmr.EncounterUpdateDao")
public class EncounterUpdateDao {
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessionFactory;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao localNodeDao;
	
	@javax.annotation.Resource(name = "encounterDAO")
	private org.openmrs.api.db.EncounterDAO encounterDao;
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	public void lock() {
		org.hibernate.Session session = sessionFactory.getCurrentSession();
		org.hibernate.FlushMode previous = session.getHibernateFlushMode();
		// La observaciÃ³n editada aÃºn debe pasar por saveObs, que crea su nueva versiÃ³n.
		// Consultar la configuraciÃ³n no debe forzar el guardado del objeto antiguo modificado.
		try {
			session.setHibernateFlushMode(org.hibernate.FlushMode.MANUAL);
			localNodeDao.getLocalServerId();
		}
		finally {
			session.setHibernateFlushMode(previous);
		}
	}
	
	public void recordSaved(Encounter encounter) {
        if (Boolean.TRUE.equals(encounter.getVoided())) return; // VOID se implementa por separado.
        String origin = localNodeDao.getLocalServerId();
        sessionFactory.getCurrentSession().flush();
        sessionFactory.getCurrentSession().doWork(c -> {
            State state = state(c, encounter.getEncounterId());
            if (state == null) return; // Un encuentro histÃ³rico necesita preparaciÃ³n explÃ­cita.
            ObjectNode snapshot = EncounterUpdateEvent.capture(encounter);
            requireIdentity(state.snapshot, snapshot);
            Set<String> changed = EncounterUpdateEvent.changes(state.snapshot, snapshot);
            if (changed.isEmpty()) return;
            long sequence;
            try (PreparedStatement q = c.prepareStatement("select encounter_sequence from synchronizationmr_local_node where singleton_id=1"); ResultSet rows = q.executeQuery()) {
                if (!rows.next()) throw new APIException("Falta el nodo local");
                sequence = Math.addExact(rows.getLong(1), 1);
            }
            long time = System.currentTimeMillis();
            for (JsonNode version : state.versions) {
                time = Math.max(time, Math.addExact(Instant.parse(version.path("occurredAt").asText()).toEpochMilli(), 1));
            }
            EncounterUpdateEvent event = new EncounterUpdateEvent(EncounterUpdateEvent.create(snapshot, changed, origin, sequence, Instant.ofEpochMilli(time)));
            event.toMetadata();
            insert(c, encounter.getEncounterId(), event);
            for (String group : changed) state.versions.set(group, event.version());
            saveState(c, encounter.getEncounterId(), snapshot, state.versions, state.persisted);
            try (PreparedStatement q = c.prepareStatement("update synchronizationmr_local_node set encounter_sequence=? where singleton_id=1")) {
                q.setLong(1, sequence); q.executeUpdate();
            }
        });
    }
	
	public void receive(Connection c, EncounterUpdateEvent event) throws SQLException {
        if (!Context.hasPrivilege(org.openmrs.util.PrivilegeConstants.EDIT_ENCOUNTERS)) {
            throw new org.openmrs.api.APIAuthenticationException("La recepciÃ³n de modificaciones requiere Edit Encounters");
        }
        Encounter target = Context.getEncounterService().getEncounterByUuid(event.encounterUuid);
        if (target == null) throw new EncounterDependencyException("Falta el encuentro original antes de su modificaciÃ³n");
        if (Boolean.TRUE.equals(target.getPatient().getVoided())
            || !event.patientUuid.equals(target.getPatient().getUuid())) throw new APIException("El encuentro no admite esta modificaciÃ³n");
        State state = state(c, target.getEncounterId());
        if (state == null) throw new EncounterDependencyException("Falta el evento CREATE original del encuentro");
        Encounter incoming = event.toMetadata();
        Set<String> winning = new LinkedHashSet<>();
        for (String group : event.changedGroups) {
            if (PatientUpdateEvent.compare(event.version(), state.versions.get(group)) > 0) {
                winning.add(group);
                state.versions.set(group, event.version());
            }
        }
        // Incluso un evento antiguo se valida y conserva; solo se aplican sus campos ganadores.
        validateProviders(c, target, incoming);
        if (!winning.isEmpty()) {
            requireTypePermission(target);
            apply(target, incoming, winning);
            requireTypePermission(target);
            org.openmrs.validator.ValidateUtil.validate(target);
            target.setChangedBy(Context.getAuthenticatedUser());
            target.setDateChanged(new java.util.Date());
            // El servicio saveEncounter propaga fecha/ubicaciÃ³n a Obs y genera UUID de versiones
            // locales. AquÃ­ solo se persisten metadatos con el DAO nativo, sin recrear resultados.
            // Las correcciones de Obs se transportan por separado mediante CORRECT_OBS.
            encounterDao.saveEncounter(target);
            sessionFactory.getCurrentSession().flush();
        }
        insert(c, target.getEncounterId(), event);
        saveState(c, target.getEncounterId(), EncounterUpdateEvent.capture(target), state.versions, state.persisted);
    }
	
	private void requireTypePermission(Encounter encounter) {
		if (!Context.getEncounterService().canEditEncounter(encounter, null)) {
			throw new org.openmrs.api.APIAuthenticationException("Falta el permiso de ediciÃ³n del tipo de encuentro");
		}
	}
	
	private void validateProviders(Connection c, Encounter target, Encounter incoming) throws SQLException {
        for (EncounterProvider provider : incoming.getActiveEncounterProviders()) {
            try (PreparedStatement q = c.prepareStatement("select encounter_id from encounter_provider where uuid=?")) {
                q.setString(1, provider.getUuid());
                try (ResultSet rows = q.executeQuery()) {
                    if (rows.next() && rows.getInt(1) != target.getEncounterId()) throw new APIException("El profesional asociado pertenece a otro encuentro");
                }
            }
        }
    }
	
	private void apply(Encounter target, Encounter incoming, Set<String> groups) {
        if (groups.contains("encounterDatetime")) {
            java.util.Date date = new java.util.Date(Math.floorDiv(incoming.getEncounterDatetime().getTime(), 1000L) * 1000L);
            Visit visit = target.getVisit();
            if (visit != null && (date.before(visit.getStartDatetime())
                || visit.getStopDatetime() != null && date.after(visit.getStopDatetime()))) {
                throw new APIException("La fecha modificada queda fuera de la visita existente");
            }
            target.setEncounterDatetime(date);
        }
        if (groups.contains("encounterTypeUuid")) target.setEncounterType(incoming.getEncounterType());
        if (groups.contains("locationUuid")) target.setLocation(incoming.getLocation());
        if (groups.contains("formUuid")) target.setForm(incoming.getForm());
        if (groups.contains("encounterProviders")) {
            Map<String, EncounterProvider> desired = new HashMap<>();
            for (EncounterProvider provider : incoming.getActiveEncounterProviders()) desired.put(provider.getUuid(), provider);
            for (EncounterProvider existing : target.getEncounterProviders()) {
                EncounterProvider replacement = desired.remove(existing.getUuid());
                existing.setVoided(replacement == null);
                existing.setVoidReason(replacement == null ? "Retirado mediante sincronizaciÃ³n" : null);
                existing.setVoidedBy(replacement == null ? Context.getAuthenticatedUser() : null);
                existing.setDateVoided(replacement == null ? new java.util.Date() : null);
                if (replacement != null) {
                    existing.setProvider(replacement.getProvider());
                    existing.setEncounterRole(replacement.getEncounterRole());
                }
                existing.setChangedBy(Context.getAuthenticatedUser());
                existing.setDateChanged(new java.util.Date());
            }
            for (EncounterProvider provider : desired.values()) {
                provider.setEncounter(target);
                provider.setCreator(Context.getAuthenticatedUser());
                provider.setDateCreated(new java.util.Date());
                target.getEncounterProviders().add(provider);
            }
        }
    }
	
	private void requireIdentity(ObjectNode before, ObjectNode after) {
		if (!before.path("encounterUuid").equals(after.path("encounterUuid"))
		        || !before.path("patientUuid").equals(after.path("patientUuid"))) {
			throw new APIException("No se puede cambiar la identidad ni el paciente de un encuentro sincronizado");
		}
	}
	
	private State state(Connection c, int id) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("select snapshot_json,versions_json from synchronizationmr_encounter_state where encounter_id=? for update")) {
            q.setInt(1, id);
            try (ResultSet rows = q.executeQuery()) {
                if (rows.next()) return new State(object(rows.getString(1)), object(rows.getString(2)), true);
            }
        }
        try (PreparedStatement q = c.prepareStatement("select payload_json from synchronizationmr_encounter_event where encounter_id=? for update")) {
            q.setInt(1, id);
            try (ResultSet rows = q.executeQuery()) {
                if (!rows.next()) return null;
                ObjectNode creation = object(rows.getString(1)), versions = MAPPER.createObjectNode();
                for (String group : EncounterUpdateEvent.GROUPS) versions.set(group, PatientUpdateEvent.version(creation));
                return new State(EncounterUpdateEvent.project(creation.path("payload")), versions, false);
            }
        }
    }
	
	private ObjectNode object(String json) {
		try {
			JsonNode value = MAPPER.readTree(json);
			if (value == null || !value.isObject())
				throw new APIException("Estado de encuentro invÃ¡lido");
			return (ObjectNode) value;
		}
		catch (Exception e) {
			throw new APIException("Falta el estado original vÃ¡lido del encuentro");
		}
	}
	
	private void insert(Connection c, int id, EncounterUpdateEvent event) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("insert into synchronizationmr_encounter_update (event_uuid,encounter_id,encounter_uuid,origin_server_id,entity_sequence,date_created,payload_json) values (?,?,?,?,?,?,?)")) {
            q.setString(1, event.eventUuid); q.setInt(2, id); q.setString(3, event.encounterUuid);
            q.setString(4, event.origin); q.setLong(5, event.sequence);
            q.setTimestamp(6, Timestamp.from(event.occurredAt)); q.setString(7, event.json); q.executeUpdate();
        }
    }
	
	private void saveState(Connection c, int id, ObjectNode snapshot, ObjectNode versions, boolean exists) throws SQLException {
        String sql = exists ? "update synchronizationmr_encounter_state set snapshot_json=?,versions_json=? where encounter_id=?"
            : "insert into synchronizationmr_encounter_state (snapshot_json,versions_json,encounter_id) values (?,?,?)";
        try (PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, snapshot.toString()); q.setString(2, versions.toString()); q.setInt(3, id); q.executeUpdate();
        }
    }
	
	private static final class State {
		
		final ObjectNode snapshot, versions;
		
		final boolean persisted;
		
		State(ObjectNode snapshot, ObjectNode versions, boolean persisted) {
			this.snapshot = snapshot;
			this.versions = versions;
			this.persisted = persisted;
		}
	}
}

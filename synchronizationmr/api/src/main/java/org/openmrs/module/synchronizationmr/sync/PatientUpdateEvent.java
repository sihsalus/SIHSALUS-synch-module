package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import org.openmrs.api.APIException;

/**
 * UPDATE versión 5. Los grupos atómicos evitan que cambios independientes se sobrescriban entre sí.
 */
public final class PatientUpdateEvent {
	
	public static final List<String> GROUPS = Collections.unmodifiableList(Arrays.asList("demographics", "names",
	    "addresses", "identifiers", "attributes"));
	
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
	        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	
	public final String json, origin, eventUuid, patientUuid;
	
	public final long sequence;
	
	public final Instant occurredAt;
	
	public final Set<String> changedGroups;
	
	private final ObjectNode root;
	
	public PatientUpdateEvent(String json) {
        try {
            if (json == null || json.length() > 1000000) { throw EventJson.invalid(); }
            JsonNode parsed = MAPPER.readTree(json);
            EventJson.fields(parsed, "schemaVersion,eventUuid,originServerId,entityType,entitySequence,operation,occurredAt,payload,changedGroups");
            if (!parsed.path("schemaVersion").isIntegralNumber() || !parsed.path("schemaVersion").canConvertToInt()
                || parsed.path("schemaVersion").intValue() != 5 || !"UPDATE".equals(EventJson.text(parsed, "operation", true))
                || !"PATIENT".equals(EventJson.text(parsed, "entityType", true))) { throw EventJson.invalid(); }
            root = (ObjectNode) parsed;
            origin = ServerId.requireValid(EventJson.text(root, "originServerId", true));
            eventUuid = EventJson.uuid(EventJson.text(root, "eventUuid", true));
            JsonNode seq = root.path("entitySequence");
            if (!seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue() < 1) { throw EventJson.invalid(); }
            sequence = seq.longValue();
            occurredAt = Instant.parse(EventJson.text(root, "occurredAt", true));
            // La captura local usa milisegundos para evitar ambigüedades de precisión en la base de datos.
            if (!Instant.ofEpochMilli(occurredAt.toEpochMilli()).equals(occurredAt)) { throw EventJson.invalid(); }
            patientUuid = EventJson.reference(EventJson.text(root.path("payload"), "patientUuid", true));
            Set<String> groups = new LinkedHashSet<>();
            for (JsonNode group : EventJson.array(root, "changedGroups", true)) {
                if (!group.isTextual() || !GROUPS.contains(group.asText()) || !groups.add(group.asText())) { throw EventJson.invalid(); }
            }
            changedGroups = Collections.unmodifiableSet(groups);
            // Reutiliza el esquema explícito de la copia del paciente; no deserializa objetos arbitrarios.
            snapshotEvent();
            this.json = json;
        } catch (APIException e) { throw e; }
        catch (Exception e) { throw EventJson.invalid(); }
    }
	
	public PatientIncomingEvent snapshotEvent() {
		ObjectNode copy = root.deepCopy();
		copy.put("schemaVersion", 4);
		copy.put("operation", "CREATE");
		copy.remove("changedGroups");
		return new PatientIncomingEvent(copy.toString());
	}
	
	public ObjectNode payload() {
		return ((ObjectNode) root.get("payload")).deepCopy();
	}
	
	public ObjectNode version() {
		return version(root);
	}
	
	public static ObjectNode version(JsonNode event) {
		ObjectNode result = MAPPER.createObjectNode();
		for (String field : Arrays.asList("occurredAt", "originServerId", "entitySequence", "eventUuid")) {
			result.set(field, event.get(field));
		}
		return result;
	}
	
	public static int compare(JsonNode a, JsonNode b) {
		int order = Instant.parse(a.path("occurredAt").asText()).compareTo(Instant.parse(b.path("occurredAt").asText()));
		if (order == 0) {
			order = a.path("originServerId").asText().compareTo(b.path("originServerId").asText());
		}
		if (order == 0) {
			order = Long.compare(a.path("entitySequence").asLong(), b.path("entitySequence").asLong());
		}
		if (order == 0) {
			order = a.path("eventUuid").asText().compareTo(b.path("eventUuid").asText());
		}
		return order;
	}
	
	public static Set<String> changes(ObjectNode before, ObjectNode after) {
        Set<String> changed = new LinkedHashSet<>();
        Iterator<String> fields = after.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if ("patientUuid".equals(field)) { continue; }
            if (!Objects.equals(before.get(field), after.get(field))) {
                changed.add(GROUPS.contains(field) ? field : "demographics");
            }
        }
        return changed;
    }
	
	public static String create(ObjectNode payload, Set<String> groups, String origin, long sequence, Instant time) {
		ObjectNode event = MAPPER.createObjectNode();
		event.put("schemaVersion", 5);
		event.put("eventUuid", UUID.randomUUID().toString());
		event.put("originServerId", origin);
		event.put("entityType", "PATIENT");
		event.put("entitySequence", sequence);
		event.put("operation", "UPDATE");
		event.put("occurredAt", time.toString());
		event.set("payload", payload);
		ArrayNode changed = event.putArray("changedGroups");
		for (String group : groups) {
			changed.add(group);
		}
		return event.toString();
	}
	
	public boolean sameContent(String other) {
		try {
			return root.equals(MAPPER.readTree(other));
		}
		catch (Exception e) {
			return false;
		}
	}
	
	public static boolean isUpdate(String json) {
		try {
			if (json == null || json.length() > 1000000) {
				throw EventJson.invalid();
			}
			return "UPDATE".equals(MAPPER.readTree(json).path("operation").asText());
		}
		catch (Exception e) {
			throw EventJson.invalid();
		}
	}
}

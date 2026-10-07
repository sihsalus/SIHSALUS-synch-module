package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import org.openmrs.Encounter;
import org.openmrs.api.APIException;

/** UPDATE v5 de metadatos. No transporta cambios de visita, Ã³rdenes ni valores de observaciones. */
public final class EncounterUpdateEvent {
	
	public static final List<String> GROUPS = Collections.unmodifiableList(Arrays.asList("encounterDatetime",
	    "encounterTypeUuid", "locationUuid", "formUuid", "encounterProviders"));
	
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
	        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	
	public final String json, origin, eventUuid, encounterUuid, patientUuid;
	
	public final long sequence;
	
	public final Instant occurredAt;
	
	public final Set<String> changedGroups;
	
	private final ObjectNode root;
	
	public EncounterUpdateEvent(String json) {
        try {
            if (json == null || json.length() > 1000000) throw EventJson.invalid();
            JsonNode parsed = MAPPER.readTree(json);
            EventJson.fields(parsed, "schemaVersion,eventUuid,originServerId,entityType,entitySequence,operation,occurredAt,payload,changedGroups");
            JsonNode schema = parsed.path("schemaVersion"), seq = parsed.path("entitySequence");
            if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue() != 5
                || !"ENCOUNTER".equals(EventJson.text(parsed, "entityType", true))
                || !"UPDATE".equals(EventJson.text(parsed, "operation", true))
                || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue() < 1) throw EventJson.invalid();
            root = (ObjectNode) parsed;
            origin = ServerId.requireValid(EventJson.text(root, "originServerId", true));
            eventUuid = EventJson.uuid(EventJson.text(root, "eventUuid", true));
            sequence = seq.longValue();
            occurredAt = Instant.parse(EventJson.text(root, "occurredAt", true));
            if (!Instant.ofEpochMilli(occurredAt.toEpochMilli()).equals(occurredAt)) throw EventJson.invalid();
            JsonNode data = root.path("payload");
            EventJson.fields(data, "encounterUuid,patientUuid,encounterDatetime,encounterTypeUuid,locationUuid,formUuid,encounterProviders");
            encounterUuid = EventJson.reference(EventJson.text(data, "encounterUuid", true));
            patientUuid = EventJson.reference(EventJson.text(data, "patientUuid", true));
            EventJson.instant(EventJson.text(data, "encounterDatetime", true));
            for (String field : Arrays.asList("encounterTypeUuid", "locationUuid", "formUuid")) {
                String value = EventJson.text(data, field, "encounterTypeUuid".equals(field));
                if (value != null) EventJson.reference(value);
            }
            Set<String> ids = new HashSet<>(), roles = new HashSet<>();
            for (JsonNode provider : EventJson.array(data, "encounterProviders", false)) {
                EventJson.fields(provider, "uuid,providerUuid,encounterRoleUuid");
                String id = EventJson.reference(EventJson.text(provider, "uuid", true));
                String professional = EventJson.reference(EventJson.text(provider, "providerUuid", true));
                String role = EventJson.reference(EventJson.text(provider, "encounterRoleUuid", true));
                if (!ids.add(id) || !roles.add(professional + "/" + role)) throw EventJson.invalid();
            }
            Set<String> groups = new LinkedHashSet<>();
            for (JsonNode group : EventJson.array(root, "changedGroups", true)) {
                if (!group.isTextual() || !GROUPS.contains(group.asText()) || !groups.add(group.asText())) throw EventJson.invalid();
            }
            changedGroups = Collections.unmodifiableSet(groups);
            this.json = json;
        } catch (APIException e) { throw e; }
        catch (Exception e) { throw EventJson.invalid(); }
    }
	
	/** Reutiliza las comprobaciones de catÃ¡logos de CREATE, sin resolver visitas ni observaciones. */
	public Encounter toMetadata() {
		ObjectNode copy = root.deepCopy();
		copy.put("schemaVersion", 2);
		copy.put("operation", "CREATE");
		copy.remove("changedGroups");
		ObjectNode data = (ObjectNode) copy.get("payload");
		data.putNull("visitUuid");
		data.put("voided", false);
		data.putNull("voidReason");
		data.putArray("obs");
		data.putArray("orderUuids");
		data.putArray("unsupportedContent");
		return new EncounterIncomingEvent(copy.toString()).toEncounter();
	}
	
	public ObjectNode version() {
		return PatientUpdateEvent.version(root);
	}
	
	public static ObjectNode capture(Encounter encounter) {
		return project(EncounterSnapshot.capture(encounter).toJson());
	}
	
	/** TambiÃ©n normaliza CREATE histÃ³ricos y la precisiÃ³n de DATETIME utilizada por OpenMRS. */
	public static ObjectNode project(JsonNode payload) {
        ObjectNode data = MAPPER.createObjectNode();
        for (String field : Arrays.asList("encounterUuid", "patientUuid", "encounterDatetime", "encounterTypeUuid", "locationUuid", "formUuid", "encounterProviders")) {
            JsonNode value = payload.get(field);
            if (value == null) throw new APIException("Falta un campo en el estado del encuentro: " + field);
            data.set(field, value.deepCopy());
        }
        Instant date = Instant.parse(data.path("encounterDatetime").asText());
        data.put("encounterDatetime", Instant.ofEpochSecond(date.getEpochSecond()).toString());
        Map<String, JsonNode> providers = new TreeMap<>();
        for (JsonNode item : data.path("encounterProviders")) {
            if (providers.put(item.path("uuid").asText(), item) != null) throw EventJson.invalid();
        }
        ArrayNode sorted = data.putArray("encounterProviders");
        for (JsonNode item : providers.values()) sorted.add(item);
        return data;
    }
	
	public static Set<String> changes(ObjectNode before, ObjectNode after) {
        Set<String> result = new LinkedHashSet<>();
        for (String group : GROUPS) if (!Objects.equals(before.get(group), after.get(group))) result.add(group);
        return result;
    }
	
	public static String create(ObjectNode payload, Set<String> groups, String origin, long sequence, Instant time) {
		ObjectNode event = MAPPER.createObjectNode();
		event.put("schemaVersion", 5);
		event.put("eventUuid", UUID.randomUUID().toString());
		event.put("originServerId", origin);
		event.put("entityType", "ENCOUNTER");
		event.put("entitySequence", sequence);
		event.put("operation", "UPDATE");
		event.put("occurredAt", time.toString());
		event.set("payload", payload);
		ArrayNode changed = event.putArray("changedGroups");
		for (String group : groups)
			changed.add(group);
		return event.toString();
	}
}

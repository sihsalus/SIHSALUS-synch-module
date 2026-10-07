package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import org.openmrs.*;
import org.openmrs.api.APIException;

/** Versiones nuevas de observaciones; los valores de UUID ya publicados son inmutables. */
public final class ObservationCorrectionEvent {
	
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
	        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	
	public final String json, origin, eventUuid, encounterUuid, patientUuid;
	
	public final long sequence;
	
	public final Instant occurredAt;
	
	private final ObjectNode root;
	
	public final Map<String, String> heads;
	
	public ObservationCorrectionEvent(String json) {
        try {
            if (json == null || json.length() > 1000000) throw EventJson.invalid();
            JsonNode parsed = MAPPER.readTree(json);
            EventJson.fields(parsed, "schemaVersion,eventUuid,originServerId,entityType,entitySequence,operation,occurredAt,payload");
            JsonNode schema = parsed.path("schemaVersion"), seq = parsed.path("entitySequence");
            if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue() != 6
                || !"ENCOUNTER".equals(EventJson.text(parsed, "entityType", true))
                || !"CORRECT_OBS".equals(EventJson.text(parsed, "operation", true))
                || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue() < 1) throw EventJson.invalid();
            root = (ObjectNode) parsed;
            origin = ServerId.requireValid(EventJson.text(root, "originServerId", true));
            eventUuid = EventJson.uuid(EventJson.text(root, "eventUuid", true));
            sequence = seq.longValue();
            occurredAt = Instant.parse(EventJson.text(root, "occurredAt", true));
            if (!Instant.ofEpochMilli(occurredAt.toEpochMilli()).equals(occurredAt)) throw EventJson.invalid();
            JsonNode data = root.path("payload");
            EventJson.fields(data, "encounterUuid,patientUuid,obs,heads");
            encounterUuid = EventJson.reference(EventJson.text(data, "encounterUuid", true));
            patientUuid = EventJson.reference(EventJson.text(data, "patientUuid", true));
            Map<String, String> roots = new HashMap<>();
            for (JsonNode item : EventJson.array(data, "obs", true)) {
                String id = EventJson.reference(EventJson.text(item, "uuid", true));
                String lineage = EventJson.reference(EventJson.text(item, "rootUuid", true));
                if (roots.put(id, lineage) != null) throw EventJson.invalid();
                if (EventJson.array(item, "groupMembers", false).size() != 0) throw EventJson.invalid();
                for (String link : Arrays.asList("previousVersionUuid", "parentGroupUuid")) {
                    String value = EventJson.text(item, link, false);
                    if (value != null) EventJson.reference(value);
                }
            }
            Map<String, String> selected = new LinkedHashMap<>();
            for (JsonNode head : EventJson.array(data, "heads", true)) {
                EventJson.fields(head, "rootUuid,headUuid");
                String lineage = EventJson.reference(EventJson.text(head, "rootUuid", true));
                String id = EventJson.reference(EventJson.text(head, "headUuid", true));
                if (!lineage.equals(roots.get(id)) || selected.put(lineage, id) != null) throw EventJson.invalid();
            }
            if (!selected.keySet().equals(new HashSet<>(roots.values()))) throw EventJson.invalid();
            heads = Collections.unmodifiableMap(selected);
            this.json = json;
        } catch (APIException e) { throw e; }
        catch (Exception e) { throw EventJson.invalid(); }
    }
	
	public ArrayNode observations() {
		return ((ArrayNode) root.path("payload").get("obs")).deepCopy();
	}
	
	public ObjectNode version() {
		return PatientUpdateEvent.version(root);
	}
	
	/** Reutiliza la validaciÃ³n de valores y catÃ¡logos; todos los UUID recibidos deben ser nuevos. */
	public List<Obs> decode(Encounter target) {
        ObjectNode envelope = root.deepCopy();
        envelope.put("schemaVersion", 2); envelope.put("operation", "CREATE");
        ObjectNode data = EncounterUpdateEvent.capture(target);
        data.putNull("visitUuid"); data.put("voided", false); data.putNull("voidReason");
        data.putArray("orderUuids"); data.putArray("unsupportedContent"); data.putArray("encounterProviders");
        ArrayNode values = data.putArray("obs");
        for (JsonNode item : observations()) {
            ObjectNode copy = (ObjectNode) item.deepCopy();
            EventJson.text(copy, "previousVoidReason", false);
            copy.remove(Arrays.asList("rootUuid", "parentGroupUuid", "previousVoidReason"));
            values.add(copy);
        }
        envelope.set("payload", data);
        Encounter reconstructed = new EncounterIncomingEvent(envelope.toString()).toEncounter();
        List<Obs> result = new ArrayList<>(reconstructed.getObsAtTopLevel(true));
        for (Obs obs : result) obs.setEncounter(target);
        return result;
    }
	
	public static String create(Encounter encounter, ArrayNode obs, Map<String, String> heads, String origin, long sequence,
	        Instant time) {
		ObjectNode event = MAPPER.createObjectNode();
		event.put("schemaVersion", 6);
		event.put("entityType", "ENCOUNTER");
		event.put("operation", "CORRECT_OBS");
		event.put("eventUuid", UUID.randomUUID().toString());
		event.put("originServerId", origin);
		event.put("entitySequence", sequence);
		event.put("occurredAt", time.toString());
		ObjectNode data = event.putObject("payload");
		data.put("encounterUuid", encounter.getUuid());
		data.put("patientUuid", encounter.getPatient().getUuid());
		data.set("obs", obs);
		ArrayNode selected = data.putArray("heads");
		for (Map.Entry<String, String> head : heads.entrySet()) {
			ObjectNode item = selected.addObject();
			item.put("rootUuid", head.getKey());
			item.put("headUuid", head.getValue());
		}
		return event.toString();
	}
}

package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import org.openmrs.Encounter;

/** Anula UUID concretos sin borrar valores ni inferir relaciones de versionado. */
public final class ObservationVoidEvent {
	
	private static final ObjectMapper M = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(
	    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	
	public final String json, origin, eventUuid, encounterUuid, patientUuid;
	
	public final long sequence;
	
	public final Instant occurredAt;
	
	private final ObjectNode root;
	
	public ObservationVoidEvent(String json) {
        try {
            if (json == null || json.length() > 1000000) throw EventJson.invalid();
            JsonNode value = M.readTree(json);
            EventJson.fields(value,"schemaVersion,eventUuid,originServerId,entityType,entitySequence,operation,occurredAt,payload");
            JsonNode schema=value.path("schemaVersion"), seq=value.path("entitySequence");
            if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue()!=9
                || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue()<1
                || !"ENCOUNTER".equals(EventJson.text(value,"entityType",true))
                || !"VOID_OBS".equals(EventJson.text(value,"operation",true))) throw EventJson.invalid();
            root=(ObjectNode)value;
            origin=ServerId.requireValid(EventJson.text(root,"originServerId",true));
            eventUuid=EventJson.uuid(EventJson.text(root,"eventUuid",true)); sequence=seq.longValue();
            occurredAt=Instant.parse(EventJson.text(root,"occurredAt",true));
            if (!occurredAt.equals(Instant.ofEpochMilli(occurredAt.toEpochMilli()))) throw EventJson.invalid();
            JsonNode payload=root.path("payload"); EventJson.fields(payload,"encounterUuid,patientUuid,observations");
            encounterUuid=EventJson.reference(EventJson.text(payload,"encounterUuid",true));
            patientUuid=EventJson.reference(EventJson.text(payload,"patientUuid",true));
            Set<String> seen=new HashSet<>();
            JsonNode items=EventJson.array(payload,"observations",true);
            if (items.size()>100) throw EventJson.invalid();
            for(JsonNode item:items) {
                EventJson.fields(item,"uuid,reason,dateVoided");
                if(!seen.add(EventJson.reference(EventJson.text(item,"uuid",true))))throw EventJson.invalid();
                if(EventJson.text(item,"reason",true).length()>255)throw EventJson.invalid();
                Instant.parse(EventJson.text(item,"dateVoided",true));
            }
            this.json=json;
        } catch(Exception ex) { throw EventJson.invalid(); }
    }
	
	public ArrayNode observations() {
		return ((ArrayNode) root.path("payload").path("observations")).deepCopy();
	}
	
	public ObjectNode version() {
		return PatientUpdateEvent.version(root);
	}
	
	public static String create(Encounter encounter, ArrayNode items, String origin, long sequence, Instant time) {
		ObjectNode root = M.createObjectNode();
		root.put("schemaVersion", 9);
		root.put("entityType", "ENCOUNTER");
		root.put("operation", "VOID_OBS");
		root.put("eventUuid", UUID.randomUUID().toString());
		root.put("originServerId", origin);
		root.put("entitySequence", sequence);
		root.put("occurredAt", time.toString());
		ObjectNode data = root.putObject("payload");
		data.put("encounterUuid", encounter.getUuid());
		data.put("patientUuid", encounter.getPatient().getUuid());
		data.set("observations", items);
		return root.toString();
	}
}

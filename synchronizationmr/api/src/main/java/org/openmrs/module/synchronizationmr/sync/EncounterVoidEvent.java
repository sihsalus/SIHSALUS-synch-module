package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.openmrs.Encounter;

/** Anulacion logica del encuentro completo; nunca solicita una eliminacion fisica. */
public final class EncounterVoidEvent {

	private static final ObjectMapper M = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(
	    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	public final String json, origin, eventUuid, encounterUuid, patientUuid, reason;

	public final long sequence;

	public final Instant occurredAt, dateVoided;

	public EncounterVoidEvent(String json) {
		try {
			if (json == null || json.length() > 1000000)
				throw EventJson.invalid();
			JsonNode root = M.readTree(json);
			EventJson.fields(root,
			    "schemaVersion,eventUuid,originServerId,entityType,entitySequence,operation,occurredAt,payload");
			JsonNode schema = root.path("schemaVersion"), seq = root.path("entitySequence");
			if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue() != 10
			        || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue() < 1
			        || !"ENCOUNTER".equals(EventJson.text(root, "entityType", true))
			        || !"VOID_ENCOUNTER".equals(EventJson.text(root, "operation", true)))
				throw EventJson.invalid();
			origin = ServerId.requireValid(EventJson.text(root, "originServerId", true));
			eventUuid = EventJson.uuid(EventJson.text(root, "eventUuid", true));
			sequence = seq.longValue();
			occurredAt = Instant.parse(EventJson.text(root, "occurredAt", true));
			JsonNode payload = root.path("payload");
			EventJson.fields(payload, "encounterUuid,patientUuid,reason,dateVoided");
			encounterUuid = EventJson.reference(EventJson.text(payload, "encounterUuid", true));
			patientUuid = EventJson.reference(EventJson.text(payload, "patientUuid", true));
			reason = EventJson.text(payload, "reason", true);
			if (reason.trim().isEmpty() || reason.length() > 255)
				throw EventJson.invalid();
			dateVoided = Instant.parse(EventJson.text(payload, "dateVoided", true));
			if (!occurredAt.equals(Instant.ofEpochMilli(occurredAt.toEpochMilli()))
			        || !dateVoided.equals(Instant.ofEpochMilli(dateVoided.toEpochMilli())))
				throw EventJson.invalid();
			this.json = json;
		}
		catch (Exception ex) {
			throw EventJson.invalid();
		}
	}

	public static String create(Encounter encounter, String origin, long sequence, Instant time) {
		ObjectNode root = M.createObjectNode();
		root.put("schemaVersion", 10);
		root.put("entityType", "ENCOUNTER");
		root.put("operation", "VOID_ENCOUNTER");
		root.put("eventUuid", UUID.randomUUID().toString());
		root.put("originServerId", origin);
		root.put("entitySequence", sequence);
		root.put("occurredAt", time.toString());
		ObjectNode data = root.putObject("payload");
		data.put("encounterUuid", encounter.getUuid());
		data.put("patientUuid", encounter.getPatient().getUuid());
		data.put("reason", encounter.getVoidReason());
		data.put("dateVoided", encounter.getDateVoided().toInstant().toString());
		return root.toString();
	}
}

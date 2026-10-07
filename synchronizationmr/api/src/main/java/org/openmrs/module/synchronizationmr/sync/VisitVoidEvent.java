package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.openmrs.Visit;

/** Anulación lógica de una visita publicada; conserva el historial clínico. */
public final class VisitVoidEvent {
	
	private static final ObjectMapper M = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(
	    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	
	public final String json, origin, eventUuid, encounterUuid, patientUuid, visitUuid, reason;
	
	public final long sequence;
	
	public final Instant occurredAt, dateVoided;
	
	public VisitVoidEvent(String json) {
		try {
			if (json == null || json.length() > 1000000)
				throw EventJson.invalid();
			JsonNode root = M.readTree(json);
			EventJson.fields(root,
			    "schemaVersion,eventUuid,originServerId,entityType,entitySequence,operation,occurredAt,payload");
			JsonNode schema = root.path("schemaVersion"), seq = root.path("entitySequence");
			if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue() != 12
			        || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue() < 1
			        || !"ENCOUNTER".equals(EventJson.text(root, "entityType", true))
			        || !"VOID_VISIT".equals(EventJson.text(root, "operation", true)))
				throw EventJson.invalid();
			origin = ServerId.requireValid(EventJson.text(root, "originServerId", true));
			eventUuid = EventJson.uuid(EventJson.text(root, "eventUuid", true));
			sequence = seq.longValue();
			occurredAt = Instant.parse(EventJson.text(root, "occurredAt", true));
			JsonNode payload = root.path("payload");
			EventJson.fields(payload, "encounterUuid,patientUuid,visitUuid,reason,dateVoided");
			encounterUuid = EventJson.reference(EventJson.text(payload, "encounterUuid", true));
			patientUuid = EventJson.reference(EventJson.text(payload, "patientUuid", true));
			visitUuid = EventJson.reference(EventJson.text(payload, "visitUuid", true));
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
	
	public static String create(Visit visit, String encounterUuid, String origin, long sequence, Instant time) {
		ObjectNode root = M.createObjectNode();
		root.put("schemaVersion", 12);
		root.put("entityType", "ENCOUNTER");
		root.put("operation", "VOID_VISIT");
		root.put("eventUuid", UUID.randomUUID().toString());
		root.put("originServerId", origin);
		root.put("entitySequence", sequence);
		root.put("occurredAt", time.toString());
		ObjectNode data = root.putObject("payload");
		data.put("encounterUuid", encounterUuid);
		data.put("patientUuid", visit.getPatient().getUuid());
		data.put("visitUuid", visit.getUuid());
		data.put("reason", visit.getVoidReason());
		data.put("dateVoided", visit.getDateVoided().toInstant().toString());
		return root.toString();
	}
}

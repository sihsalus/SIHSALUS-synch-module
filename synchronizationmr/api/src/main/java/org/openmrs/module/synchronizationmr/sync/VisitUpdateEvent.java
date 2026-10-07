package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.openmrs.Visit;

/** Inicio y fin se versionan juntos para no combinar intervalos incompatibles. */
public final class VisitUpdateEvent {
	
	private static final ObjectMapper M = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(
	    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	
	public final String json, origin, eventUuid, encounterUuid, patientUuid, visitUuid;
	
	public final long sequence;
	
	public final boolean full;
	
	public final Instant occurredAt;
	
	public final ObjectNode root, snapshot;
	
	public VisitUpdateEvent(String json) {
		try {
			if (json == null || json.length() > 1000000)
				throw EventJson.invalid();
			root = (ObjectNode) M.readTree(json);
			EventJson.fields(root,
			    "schemaVersion,eventUuid,originServerId,entityType,entitySequence,operation,occurredAt,payload");
			JsonNode schema = root.path("schemaVersion"), seq = root.path("entitySequence");
			if (!schema.isIntegralNumber() || !schema.canConvertToInt()
			        || (schema.intValue() != 11 && schema.intValue() != 13) || !seq.isIntegralNumber()
			        || !seq.canConvertToLong() || seq.longValue() < 1
			        || !"ENCOUNTER".equals(EventJson.text(root, "entityType", true))
			        || !"UPDATE_VISIT".equals(EventJson.text(root, "operation", true)))
				throw EventJson.invalid();
			full = schema.intValue() == 13;
			origin = ServerId.requireValid(EventJson.text(root, "originServerId", true));
			eventUuid = EventJson.uuid(EventJson.text(root, "eventUuid", true));
			sequence = seq.longValue();
			occurredAt = Instant.parse(EventJson.text(root, "occurredAt", true));
			if (!occurredAt.equals(Instant.ofEpochMilli(occurredAt.toEpochMilli())))
				throw EventJson.invalid();
			snapshot = (ObjectNode) root.path("payload");
			EventJson.fields(snapshot, "encounterUuid,patientUuid,visitUuid,startDatetime,stopDatetime"
			        + (full ? ",metadata" : ""));
			if (full)
				VisitMetadata.validate(snapshot.get("metadata"));
			encounterUuid = EventJson.reference(EventJson.text(snapshot, "encounterUuid", true));
			patientUuid = EventJson.reference(EventJson.text(snapshot, "patientUuid", true));
			visitUuid = EventJson.reference(EventJson.text(snapshot, "visitUuid", true));
			Instant start = Instant.parse(EventJson.text(snapshot, "startDatetime", true));
			String stop = EventJson.text(snapshot, "stopDatetime", false);
			if (!snapshot.has("stopDatetime") || !start.equals(start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)))
				throw EventJson.invalid();
			if (stop != null) {
				Instant end = Instant.parse(stop);
				if (end.isBefore(start) || !end.equals(end.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)))
					throw EventJson.invalid();
			}
			this.json = json;
		}
		catch (Exception ex) {
			throw EventJson.invalid();
		}
	}
	
	public ObjectNode version() {
		return PatientUpdateEvent.version(root);
	}
	
	public static ObjectNode interval(Visit visit) {
		ObjectNode p = M.createObjectNode();
		p.put("startDatetime", date(visit.getStartDatetime()));
		p.put("stopDatetime", date(visit.getStopDatetime()));
		return p;
	}
	
	private static String date(Date date) {
		return date == null ? null : date.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
	}
	
	public static String create(Visit visit, String encounter, String origin, long sequence, Instant time) {
		ObjectNode r = M.createObjectNode();
		r.put("schemaVersion", 11);
		r.put("entityType", "ENCOUNTER");
		r.put("operation", "UPDATE_VISIT");
		r.put("eventUuid", UUID.randomUUID().toString());
		r.put("originServerId", origin);
		r.put("entitySequence", sequence);
		r.put("occurredAt", time.toString());
		ObjectNode p = interval(visit);
		p.put("encounterUuid", encounter);
		p.put("patientUuid", visit.getPatient().getUuid());
		p.put("visitUuid", visit.getUuid());
		r.set("payload", p);
		return r.toString();
	}
	
	/** Esquema ampliado; el esquema 11 continúa admitido para el historial. */
	public static String createFull(Visit visit, String encounter, String origin, long sequence, Instant time) {
		try {
			ObjectNode event = (ObjectNode) M.readTree(create(visit, encounter, origin, sequence, time));
			event.put("schemaVersion", 13);
			((ObjectNode) event.path("payload")).set("metadata", VisitMetadata.snapshot(visit));
			return event.toString();
		}
		catch (Exception ex) {
			throw EventJson.invalid();
		}
	}
	
	public static ObjectNode current(Visit visit) {
		ObjectNode snapshot = interval(visit);
		snapshot.set("metadata", VisitMetadata.snapshot(visit));
		return snapshot;
	}
	
}

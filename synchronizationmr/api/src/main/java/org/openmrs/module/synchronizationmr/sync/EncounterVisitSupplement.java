package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Arrays;
import org.openmrs.Visit;
import static org.openmrs.module.synchronizationmr.sync.EventJson.*;

/** Complemento explícito e inmutable: conserva el CREATE antiguo y fecha su preparación. */
public final class EncounterVisitSupplement {
	
	private static final ObjectMapper M = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(
	    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	
	public final String original;
	
	public final JsonNode visit;
	
	private EncounterVisitSupplement(String original, JsonNode visit) {
		this.original = original;
		this.visit = visit;
	}
	
	public static EncounterVisitSupplement read(String json) {
		try {
			JsonNode wire = M.readTree(json);
			if (wire != null && wire.path("schemaVersion").isIntegralNumber()
			        && !wire.path("schemaVersion").canConvertToInt())
				throw invalid();
			if (wire == null || !wire.isObject())
				throw invalid();
			if (!wire.path("schemaVersion").isIntegralNumber() || wire.path("schemaVersion").intValue() != 8)
				return null;
			fields(
			    wire,
			    "schemaVersion,eventUuid,originServerId,entityType,entitySequence,operation,occurredAt,payload,originalEventJson,visitSupplement,preparedAt");
			String original = text(wire, "originalEventJson", true);
			JsonNode source = M.readTree(original);
			if (!source.path("schemaVersion").isIntegralNumber() || source.path("schemaVersion").intValue() != 3
			        || !"CREATE".equals(source.path("operation").asText())
			        || !source.path("payload").path("visit").path("unsupportedAttributes").asBoolean())
				throw invalid();
			ObjectNode copy = ((ObjectNode) wire).deepCopy();
			copy.remove(Arrays.asList("originalEventJson", "visitSupplement", "preparedAt"));
			copy.put("schemaVersion", 3);
			if (!source.equals(copy))
				throw invalid();
			Instant prepared = Instant.parse(text(wire, "preparedAt", true));
			if (prepared.isBefore(Instant.parse(text(source, "occurredAt", true))))
				throw invalid();
			JsonNode visit = wire.path("visitSupplement");
			if (!visit.isObject() || !visit.has("attributes") || flag(visit, "unsupportedAttributes"))
				throw invalid();
			array(visit, "attributes", true);
			ObjectNode legacy = ((ObjectNode) visit).deepCopy();
			legacy.remove("attributes");
			legacy.put("unsupportedAttributes", true);
			if (!normalized(legacy).equals(normalized(source.path("payload").path("visit"))))
				throw invalid();
			return new EncounterVisitSupplement(original, visit.deepCopy());
		}
		catch (Exception e) {
			throw invalid();
		}
	}
	
	private static JsonNode normalized(JsonNode visit) {
		ObjectNode copy = ((ObjectNode) visit).deepCopy();
		for (String field : Arrays.asList("startDatetime", "stopDatetime")) {
			String value = text(copy, field, false);
			if (value != null)
				copy.put(field, Instant.parse(value).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
		}
		return copy;
	}
	
	public static String prepare(String original, Visit visit) {
		try {
			ObjectNode wire = (ObjectNode) M.readTree(original);
			wire.put("schemaVersion", 8);
			wire.put("originalEventJson", original);
			Instant now = Instant.now(), event = Instant.parse(wire.path("occurredAt").asText());
			wire.put("preparedAt", (now.isAfter(event) ? now : event).toString());
			wire.set("visitSupplement", EncounterVisitSnapshot.serialize(visit));
			String json = wire.toString();
			read(json);
			new EncounterIncomingEvent(json);
			return json;
		}
		catch (Exception e) {
			throw invalid();
		}
	}
}

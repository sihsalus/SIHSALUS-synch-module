package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Date;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import static org.openmrs.module.synchronizationmr.sync.EventJson.*;

/** Visit dependency carried by an encounter CREATE, not a separate event stream. */
final class EncounterVisitSnapshot {
	
	private EncounterVisitSnapshot() {
	}
	
	static JsonNode serialize(Visit visit) {
		if (visit == null)
			return JsonNodeFactory.instance.nullNode();
		ObjectNode data = JsonNodeFactory.instance.objectNode();
		data.put("uuid", id(visit));
		data.put("patientUuid", id(visit.getPatient()));
		data.put("visitTypeUuid", id(visit.getVisitType()));
		data.put("locationUuid", id(visit.getLocation()));
		data.put("indicationUuid", id(visit.getIndication()));
		data.put("startDatetime", date(visit.getStartDatetime()));
		data.put("stopDatetime", date(visit.getStopDatetime()));
		data.put("voided", Boolean.TRUE.equals(visit.getVoided()));
		data.put("voidReason", visit.getVoidReason());
		// Custom attribute value references may be local IDs or external files.
		// Preserve the fact they exist and reject reception rather than silently discard them.
		data.put("unsupportedAttributes", visit.getAttributes() != null && !visit.getAttributes().isEmpty());
		return data;
	}
	
	static Visit resolve(JsonNode payload, Patient patient, Date encounterDate) {
		String visitId = text(payload, "visitUuid", false);
		JsonNode data = payload.get("visit");
		if (data == null)
			throw invalid();
		if (visitId == null) {
			if (!data.isNull())
				throw invalid();
			return null;
		}
		fields(
		    data,
		    "uuid,patientUuid,visitTypeUuid,locationUuid,indicationUuid,startDatetime,stopDatetime,voided,voidReason,unsupportedAttributes");
		if (!reference(visitId).equals(reference(text(data, "uuid", true)))
		        || !patient.getUuid().equals(reference(text(data, "patientUuid", true))))
			throw invalid();
		if (flag(data, "unsupportedAttributes")) {
			throw new APIException("La visita contiene atributos cuyo transporte aun no esta admitido");
		}
		if (flag(data, "voided"))
			throw invalid();
		Visit expected = new Visit();
		expected.setUuid(visitId);
		expected.setPatient(patient);
		expected.setVisitType(Context.getVisitService().getVisitTypeByUuid(reference(text(data, "visitTypeUuid", true))));
		if (expected.getVisitType() == null || Boolean.TRUE.equals(expected.getVisitType().getRetired()))
			throw dependency("visitTypeUuid");
		String location = text(data, "locationUuid", false), indication = text(data, "indicationUuid", false);
		if (location != null) {
			expected.setLocation(Context.getLocationService().getLocationByUuid(reference(location)));
			if (expected.getLocation() == null || Boolean.TRUE.equals(expected.getLocation().getRetired()))
				throw dependency("visit.locationUuid");
		}
		if (indication != null) {
			expected.setIndication(Context.getConceptService().getConceptByUuid(reference(indication)));
			if (expected.getIndication() == null || Boolean.TRUE.equals(expected.getIndication().getRetired()))
				throw dependency("visit.indicationUuid");
		}
		expected.setStartDatetime(nativeDate(instant(text(data, "startDatetime", true))));
		expected.setStopDatetime(nativeDate(instant(text(data, "stopDatetime", false))));
		expected.setVoidReason(text(data, "voidReason", false));
		if (encounterDate == null
		        || encounterDate.getTime() / 1000 < expected.getStartDatetime().getTime() / 1000
		        || (expected.getStopDatetime() != null && (expected.getStopDatetime().before(expected.getStartDatetime()) || encounterDate
		                .getTime() / 1000 > expected.getStopDatetime().getTime() / 1000)))
			throw invalid();
		Visit existing = Context.getVisitService().getVisitByUuid(visitId);
		if (existing == null)
			return expected;
		// CREATE never overwrites an existing visit or attaches to a different patient's visit.
		if (!comparisonSnapshot(existing).equals(comparisonSnapshot(expected))) {
			throw new APIException("La visita existente difiere del evento; requiere conciliacion, no se sobrescribe");
		}
		return existing;
	}
	
	private static Date nativeDate(Date value) {
		return value == null ? null : new Date(Math.floorDiv(value.getTime(), 1000L) * 1000L);
	}
	
	private static String id(OpenmrsObject value) {
		return value == null ? null : reference(value.getUuid());
	}
	
	private static String date(Date value) {
		return value == null ? null : java.time.Instant.ofEpochMilli(value.getTime()).toString();
	}
	
	private static JsonNode comparisonSnapshot(Visit visit) {
		ObjectNode data = (ObjectNode) serialize(visit);
		// Compare at native database precision, without changing the original event.
		for (String field : new String[] { "startDatetime", "stopDatetime" }) {
			String value = text(data, field, false);
			if (value != null)
				data.put(field, java.time.Instant.parse(value).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
		}
		return data;
	}
}

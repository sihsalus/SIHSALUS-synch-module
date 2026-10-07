package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import static org.openmrs.module.synchronizationmr.sync.EventJson.*;

/** Dependencia de visita transportada por CREATE, sin un flujo separado de eventos. */
public final class EncounterVisitSnapshot {
	
	private static final String TEXT = "org.openmrs.customdatatype.datatype.FreeTextDatatype";
	
	private EncounterVisitSnapshot() {
	}
	
	public static JsonNode serialize(Visit visit) {
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
        boolean unsupported = false;
        if (visit.getAttributes() != null && !visit.getAttributes().isEmpty()) {
            com.fasterxml.jackson.databind.node.ArrayNode attributes = data.putArray("attributes");
            List<VisitAttribute> sorted = new ArrayList<>(visit.getAttributes());
            sorted.sort(Comparator.comparing(VisitAttribute::getUuid));
            for (VisitAttribute attribute : sorted) {
                VisitAttributeType type = attribute.getAttributeType();
                if (type == null || !VisitMetadata.supported(type.getDatatypeClassname())) { unsupported = true; continue; }
                ObjectNode item = attributes.addObject();
                item.put("uuid", id(attribute)); item.put("typeUuid", id(type));
                item.put("datatype", type.getDatatypeClassname()); item.put("datatypeConfig", type.getDatatypeConfig());
                item.put("value", attribute.getValueReference());
                item.put("voided", Boolean.TRUE.equals(attribute.getVoided())); item.put("voidReason", attribute.getVoidReason());
            }
        }
        data.put("unsupportedAttributes", unsupported);
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
		    "uuid,patientUuid,visitTypeUuid,locationUuid,indicationUuid,startDatetime,stopDatetime,voided,voidReason,unsupportedAttributes,attributes");
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
        if (data.has("attributes")) {
            Set<String> ids = new HashSet<>();
            for (JsonNode item : array(data, "attributes", false)) {
                fields(item, "uuid,typeUuid,datatype,datatypeConfig,value,voided,voidReason");
                VisitAttribute attribute = new VisitAttribute(); attribute.setUuid(unique(item, ids));
                VisitAttributeType type = Context.getVisitService().getVisitAttributeTypeByUuid(reference(text(item, "typeUuid", true)));
                if (type == null || Boolean.TRUE.equals(type.getRetired())) throw dependency("visit.attribute.typeUuid");
                if (!VisitMetadata.supported(text(item,"datatype",true)) || !Objects.equals(text(item,"datatype",true),type.getDatatypeClassname())
                    || !Objects.equals(type.getDatatypeConfig(),text(item,"datatypeConfig",false))) throw invalid();
                String value = text(item,"value",true);
                if (value.length() > 65535) throw invalid();
                VisitMetadata.validateConcept(type.getDatatypeClassname(),value);
                attribute.setAttributeType(type); attribute.setValueReferenceInternal(value);
                attribute.setVoided(flag(item,"voided")); attribute.setVoidReason(text(item,"voidReason",false));
                if (attribute.getVoided()) {
                    if (attribute.getVoidReason()==null || attribute.getVoidReason().trim().isEmpty()) throw invalid();
                    attribute.setVoidedBy(Context.getAuthenticatedUser()); attribute.setDateVoided(new Date());
                }
                expected.addAttribute(attribute);
            }
        }
		if (encounterDate == null
		        || encounterDate.getTime() / 1000 < expected.getStartDatetime().getTime() / 1000
		        || (expected.getStopDatetime() != null && (expected.getStopDatetime().before(expected.getStartDatetime()) || encounterDate
		                .getTime() / 1000 > expected.getStopDatetime().getTime() / 1000)))
			throw invalid();
		Visit existing = Context.getVisitService().getVisitByUuid(visitId);
		if (existing == null) {
            for (VisitAttribute attribute : expected.getAttributes()) {
                if (Context.getVisitService().getVisitAttributeByUuid(attribute.getUuid()) != null) throw conflict();
            }
			return expected;
        }
		// CREATE no sobrescribe una visita existente ni cambia su paciente.
		ObjectNode actual=(ObjectNode)comparisonSnapshot(existing), original=(ObjectNode)comparisonSnapshot(expected);
        if (Context.getRegisteredComponent("synchronizationmr.VisitVoidDao",
            org.openmrs.module.synchronizationmr.api.dao.VisitVoidDao.class).isPublished(existing)) {
            // La instantánea anterior no restaura una visita anulada. Los demás datos deben coincidir.
            for (ObjectNode snapshot : new ObjectNode[]{actual,original}) {
                snapshot.remove("voided"); snapshot.remove("voidReason");
                for (JsonNode attribute : snapshot.path("attributes")) {
                    ((ObjectNode)attribute).remove("voided"); ((ObjectNode)attribute).remove("voidReason");
                }
            }
        }
        if (!actual.equals(original) && Context.getRegisteredComponent("synchronizationmr.VisitUpdateDao",
            org.openmrs.module.synchronizationmr.api.dao.VisitUpdateDao.class).hasCurrentInterval(existing)) {
            // Un CREATE retrasado conserva su intervalo original, sin reabrir la visita versionada.
            for (String field : new String[]{"startDatetime","stopDatetime"}) {actual.remove(field);original.remove(field);}
        }
        if (!actual.equals(original) && Context.getRegisteredComponent("synchronizationmr.VisitUpdateDao",
            org.openmrs.module.synchronizationmr.api.dao.VisitUpdateDao.class).hasCurrentMetadata(existing)) {
            // Un CREATE retrasado no revierte los metadatos ya versionados de la visita.
            for(String field:new String[]{"visitTypeUuid","locationUuid","indicationUuid","attributes"}) {
                actual.remove(field);original.remove(field);
            }
        }
        if (!actual.equals(original)) {
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
		// Compara con la precisión nativa de la base sin cambiar el evento original.
		for (String field : new String[] { "startDatetime", "stopDatetime" }) {
			String value = text(data, field, false);
			if (value != null)
				data.put(field, java.time.Instant.parse(value).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
		}
		return data;
	}
}

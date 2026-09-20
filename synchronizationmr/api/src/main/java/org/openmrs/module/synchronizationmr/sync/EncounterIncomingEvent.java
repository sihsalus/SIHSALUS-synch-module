package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.function.Function;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import static org.openmrs.module.synchronizationmr.sync.EventJson.*;

/** Reconstructs one encounter snapshot; missing dependencies never create partial encounters. */
public final class EncounterIncomingEvent {
	
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
	        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	
	public final JsonNode root;
	
	public final String json, origin, eventUuid, encounterUuid, patientUuid;
	
	public final long sequence;
	
	public final Date occurredAt;
	
	public EncounterIncomingEvent(String json) {
		if (json == null || json.length() > 1000000) {
			throw invalid();
		}
		try {
			root = MAPPER.readTree(json);
		}
		catch (Exception e) {
			throw invalid();
		}
		fields(root, "schemaVersion,eventUuid,originServerId,entityType,entitySequence,operation,occurredAt,payload");
		JsonNode version = root.get("schemaVersion"), number = root.get("entitySequence");
		if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 2
		        || !"ENCOUNTER".equals(text(root, "entityType", true)) || !"CREATE".equals(text(root, "operation", true))
		        || number == null || !number.isIntegralNumber() || !number.canConvertToLong() || number.longValue() < 1) {
			throw invalid();
		}
		sequence = number.longValue();
		origin = text(root, "originServerId", true);
		if (!ServerId.isValid(origin)) {
			throw invalid();
		}
		eventUuid = uuid(text(root, "eventUuid", true));
		occurredAt = instant(text(root, "occurredAt", true));
		JsonNode data = root.get("payload");
		fields(
		    data,
		    "encounterUuid,patientUuid,encounterDatetime,encounterTypeUuid,locationUuid,formUuid,visitUuid,voided,voidReason,encounterProviders,obs,orderUuids,unsupportedContent");
		encounterUuid = reference(text(data, "encounterUuid", true));
		patientUuid = reference(text(data, "patientUuid", true));
		this.json = json;
	}
	
	public boolean sameContent(String other) {
		try {
			return other != null && root.equals(MAPPER.readTree(other));
		}
		catch (Exception e) {
			return false;
		}
	}
	
	public Encounter toEncounter() {
        JsonNode data = root.get("payload");
        if (array(data, "unsupportedContent", false).size() != 0) {
            throw new APIException("El encuentro contiene diagnósticos o condiciones aún no admitidos");
        }
        Set<String> orderIds = new HashSet<>();
        for (JsonNode id : array(data, "orderUuids", false)) {
            if (!id.isTextual() || !orderIds.add(reference(id.textValue()))) throw invalid();
        }
        Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
        if (patient == null || Boolean.TRUE.equals(patient.getVoided())) {
            throw new APIException("PATIENT_NOT_AVAILABLE: el paciente debe existir antes de recibir su encuentro");
        }
        Encounter encounter = new Encounter();
        encounter.setUuid(encounterUuid);
        encounter.setPatient(patient);
        encounter.setEncounterDatetime(instant(text(data, "encounterDatetime", true)));
        encounter.setEncounterType(resolve(data, "encounterTypeUuid", true, Context.getEncounterService()::getEncounterTypeByUuid));
        encounter.setLocation(resolve(data, "locationUuid", false, Context.getLocationService()::getLocationByUuid));
        encounter.setForm(resolve(data, "formUuid", false, Context.getFormService()::getFormByUuid));
        Visit visit = resolve(data, "visitUuid", false, Context.getVisitService()::getVisitByUuid);
        if (visit != null && (Boolean.TRUE.equals(visit.getVoided()) || !patientUuid.equals(visit.getPatient().getUuid()))) { throw invalid(); }
        encounter.setVisit(visit);
        encounter.setVoided(flag(data, "voided"));
        encounter.setVoidReason(text(data, "voidReason", false));
        Set<String> providerIds = new HashSet<>(), providerRoles = new HashSet<>();
        for (JsonNode item : array(data, "encounterProviders", false)) {
            fields(item, "uuid,providerUuid,encounterRoleUuid");
            EncounterProvider provider = new EncounterProvider();
            provider.setUuid(unique(item, providerIds));
            provider.setProvider(resolve(item, "providerUuid", true, Context.getProviderService()::getProviderByUuid));
            provider.setEncounterRole(resolve(item, "encounterRoleUuid", true, Context.getEncounterService()::getEncounterRoleByUuid));
            if (!providerRoles.add(provider.getProvider().getUuid() + "/" + provider.getEncounterRole().getUuid())) { throw invalid(); }
            provider.setEncounter(encounter);
            encounter.getEncounterProviders().add(provider);
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode item : array(data, "obs", false)) { encounter.addObs(observation(item, encounter, seen, 0)); }
        return encounter;
    }
	
	private Obs observation(JsonNode item, Encounter encounter, Set<String> seen, int depth) {
        if (depth > 50 || seen.size() >= 1000) { throw invalid(); }
        fields(item, "uuid,conceptUuid,obsDatetime,locationUuid,orderUuid,valueNumeric,valueText,valueDatetime,valueCodedUuid,valueCodedNameUuid,valueDrugUuid,valueModifier,comment,accessionNumber,status,interpretation,voided,voidReason,previousVersionUuid,valueComplex,complexDataIncluded,groupMembers");
        String id = unique(item, seen);
        if (Context.getObsService().getObsByUuid(id) != null) { throw conflict(); }
        if (text(item, "valueComplex", false) != null || flag(item, "complexDataIncluded")) {
            throw new APIException("No se admiten observaciones complejas sin transportar sus archivos");
        }
        if (text(item, "orderUuid", false) != null) reference(text(item, "orderUuid", false));
        Obs obs = new Obs();
        obs.setUuid(id);
        obs.setPerson(encounter.getPatient());
        obs.setEncounter(encounter);
        obs.setConcept(resolve(item, "conceptUuid", true, Context.getConceptService()::getConceptByUuid));
        if (obs.getConcept().isComplex()) { throw new APIException("El concepto requiere datos complejos no admitidos"); }
        obs.setObsDatetime(instant(text(item, "obsDatetime", true)));
        obs.setLocation(resolve(item, "locationUuid", false, Context.getLocationService()::getLocationByUuid));
        JsonNode numeric = item.get("valueNumeric");
        if (numeric != null && !numeric.isNull()) {
            if (!numeric.isNumber() || !Double.isFinite(numeric.doubleValue())) { throw invalid(); }
            obs.setValueNumeric(numeric.doubleValue());
        }
        obs.setValueText(text(item, "valueText", false));
        obs.setValueDatetime(instant(text(item, "valueDatetime", false)));
        obs.setValueCoded(resolve(item, "valueCodedUuid", false, Context.getConceptService()::getConceptByUuid));
        ConceptName name = resolve(item, "valueCodedNameUuid", false, Context.getConceptService()::getConceptNameByUuid);
        if (name != null && (Boolean.TRUE.equals(name.getVoided()) || obs.getValueCoded() == null
                || !obs.getValueCoded().getUuid().equals(name.getConcept().getUuid()))) { throw invalid(); }
        obs.setValueCodedName(name);
        obs.setValueDrug(resolve(item, "valueDrugUuid", false, Context.getConceptService()::getDrugByUuid));
        obs.setValueModifier(text(item, "valueModifier", false));
        obs.setComment(text(item, "comment", false));
        obs.setAccessionNumber(text(item, "accessionNumber", false));
        String status = text(item, "status", false), interpretation = text(item, "interpretation", false);
        try {
            obs.setStatus(status == null ? null : Obs.Status.valueOf(status));
            obs.setInterpretation(interpretation == null ? null : Obs.Interpretation.valueOf(interpretation));
        } catch (IllegalArgumentException e) { throw invalid(); }
        obs.setVoided(flag(item, "voided"));
        obs.setVoidReason(text(item, "voidReason", false));
        for (JsonNode child : array(item, "groupMembers", false)) { obs.addGroupMember(observation(child, encounter, seen, depth + 1)); }
        return obs;
    }
	
	private <T extends OpenmrsObject> T resolve(JsonNode node, String field, boolean required, Function<String, T> lookup) {
		String id = text(node, field, required);
		if (id == null) {
			return null;
		}
		T value = lookup.apply(reference(id));
		if (value == null
		        || (value instanceof OpenmrsMetadata && Boolean.TRUE.equals(((OpenmrsMetadata) value).getRetired()))
		        || (value instanceof Concept && Boolean.TRUE.equals(((Concept) value).getRetired()))) {
			throw dependency(field);
		}
		return value;
	}
}

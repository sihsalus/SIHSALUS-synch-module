package org.openmrs.module.synchronizationmr.sync;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.springframework.stereotype.Component;

/** Copia explícita del encuentro y sus observaciones al capturar el alta; no serializa Hibernate. */
@Component("synchronizationmr.EncounterCreationPayloadSerializer")
public class EncounterCreationPayloadSerializer {
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	public String serialize(Encounter encounter, String origin, long sequence, String eventUuid, Date created) {
        ObjectNode event = mapper.createObjectNode();
        event.put("schemaVersion", 2);
        event.put("entityType", "ENCOUNTER");
        event.put("operation", "CREATE");
        event.put("originServerId", ServerId.requireValid(origin));
        event.put("entitySequence", sequence);
        event.put("eventUuid", eventUuid);
        event.put("occurredAt", instant(created));
        ObjectNode data = event.putObject("payload");
        data.put("encounterUuid", required(encounter));
        data.put("patientUuid", required(encounter.getPatient()));
        data.put("encounterDatetime", instant(encounter.getEncounterDatetime()));
        data.put("encounterTypeUuid", required(encounter.getEncounterType()));
        data.put("locationUuid", reference(encounter.getLocation()));
        data.put("formUuid", reference(encounter.getForm()));
        data.put("visitUuid", reference(encounter.getVisit()));
        data.put("voided", Boolean.TRUE.equals(encounter.getVoided()));
        data.put("voidReason", encounter.getVoidReason());
        ArrayNode providers = data.putArray("encounterProviders");
        for (EncounterProvider provider : encounter.getActiveEncounterProviders()) {
            ObjectNode item = providers.addObject();
            item.put("uuid", required(provider));
            item.put("providerUuid", required(provider.getProvider()));
            item.put("encounterRoleUuid", required(provider.getEncounterRole()));
        }
        ArrayNode observations = data.putArray("obs");
        Set<String> seen = new HashSet<>();
        for (Obs obs : encounter.getObsAtTopLevel(true)) {
            observations.add(observation(obs, seen, 0));
        }
        // Solo referencias: las órdenes tendrán eventos y secuencias independientes.
        ArrayNode orders = data.putArray("orderUuids");
        for (Order order : encounter.getOrders()) { orders.add(required(order)); }
        // Estas entidades no se incluyen silenciosamente como si fueran observaciones.
        ArrayNode pending = data.putArray("unsupportedContent");
        if (encounter.getDiagnoses() != null && !encounter.getDiagnoses().isEmpty()) { pending.add("DIAGNOSES"); }
        if (encounter.getConditions(true) != null && !encounter.getConditions(true).isEmpty()) { pending.add("CONDITIONS"); }
        try { return mapper.writeValueAsString(event); }
        catch (java.io.IOException failure) { throw new APIException("No se pudo generar el JSON del encuentro", failure); }
    }
	
	private ObjectNode observation(Obs obs, Set<String> seen, int depth) {
		String uuid = required(obs);
		if (depth > 50 || !seen.add(uuid)) {
			throw new APIException("La estructura de observaciones contiene ciclos, duplicados o demasiados niveles");
		}
		ObjectNode item = mapper.createObjectNode();
		item.put("uuid", uuid);
		item.put("conceptUuid", required(obs.getConcept()));
		item.put("obsDatetime", instant(obs.getObsDatetime()));
		item.put("locationUuid", reference(obs.getLocation()));
		item.put("orderUuid", reference(obs.getOrder()));
		item.put("valueNumeric", obs.getValueNumeric());
		item.put("valueText", obs.getValueText());
		item.put("valueDatetime", instant(obs.getValueDatetime()));
		item.put("valueCodedUuid", reference(obs.getValueCoded()));
		item.put("valueCodedNameUuid", reference(obs.getValueCodedName()));
		item.put("valueDrugUuid", reference(obs.getValueDrug()));
		item.put("valueModifier", obs.getValueModifier());
		item.put("comment", obs.getComment());
		item.put("accessionNumber", obs.getAccessionNumber());
		item.put("status", obs.getStatus() == null ? null : obs.getStatus().name());
		item.put("interpretation", obs.getInterpretation() == null ? null : obs.getInterpretation().name());
		item.put("voided", Boolean.TRUE.equals(obs.getVoided()));
		item.put("voidReason", obs.getVoidReason());
		item.put("previousVersionUuid", reference(obs.getPreviousVersion()));
		// valueComplex es solo una referencia; no contiene el archivo. El receptor futuro debe tratarlo explícitamente.
		item.put("valueComplex", obs.getValueComplex());
		item.put("complexDataIncluded", false);
		ArrayNode members = item.putArray("groupMembers");
		if (obs.getGroupMembers(true) != null) {
			for (Obs member : obs.getGroupMembers(true)) {
				members.add(observation(member, seen, depth + 1));
			}
		}
		return item;
	}
	
	private String required(OpenmrsObject value) {
		if (value == null || value.getUuid() == null || value.getUuid().trim().isEmpty()) {
			throw new APIException("Falta el UUID de una entidad requerida por el encuentro");
		}
		return value.getUuid();
	}
	
	private String reference(OpenmrsObject value) {
		return value == null ? null : required(value);
	}
	
	private String instant(Date value) {
		return value == null ? null : java.time.Instant.ofEpochMilli(value.getTime()).toString();
	}
}

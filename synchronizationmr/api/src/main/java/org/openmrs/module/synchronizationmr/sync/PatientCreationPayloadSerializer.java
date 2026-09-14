/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PersonName;
import org.openmrs.PersonAddress;
import org.openmrs.api.APIException;
import org.springframework.stereotype.Component;

/**
 * Copia explícita de los datos básicos de creación; nunca serializa el grafo Hibernate del
 * paciente.
 */
@Component("synchronizationmr.PatientCreationPayloadSerializer")
public class PatientCreationPayloadSerializer {
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	public String serialize(Patient patient, String origin, long sequence, String eventUuid, Date created) {
		ObjectNode event = mapper.createObjectNode();
		event.put("schemaVersion", 2);
		event.put("eventUuid", required(eventUuid, "evento"));
		event.put("originNodeUuid", required(origin, "nodo de origen"));
		event.put("entityType", "PATIENT");
		event.put("entitySequence", sequence);
		event.put("operation", "CREATE");
		event.put("occurredAt", created.toInstant().toString());
		ObjectNode data = event.putObject("payload");
		data.put("patientUuid", required(patient.getUuid(), "paciente"));
		data.put("gender", patient.getGender());
		// Una fecha de nacimiento es una fecha civil, no un instante convertido a UTC.
		data.put("birthdate", dateOnly(patient.getBirthdate()));
		data.put("birthdateEstimated", Boolean.TRUE.equals(patient.getBirthdateEstimated()));
		data.put("dead", Boolean.TRUE.equals(patient.getDead()));
		data.put("deathDate", patient.getDeathDate() == null ? null : patient.getDeathDate().toInstant().toString());
		data.put("deathdateEstimated", Boolean.TRUE.equals(patient.getDeathdateEstimated()));
		data.put("causeOfDeathUuid",
		    patient.getCauseOfDeath() == null ? null : required(patient.getCauseOfDeath().getUuid(), "causa de muerte"));
		data.put("causeOfDeathNonCoded", patient.getCauseOfDeathNonCoded());
		ArrayNode names = data.putArray("names");
		for (PersonName name : patient.getNames()) {
			if (Boolean.TRUE.equals(name.getVoided())) {
				continue;
			}
			ObjectNode item = names.addObject();
			item.put("uuid", required(name.getUuid(), "nombre"));
			item.put("preferred", Boolean.TRUE.equals(name.getPreferred()));
			item.put("prefix", name.getPrefix());
			item.put("givenName", name.getGivenName());
			item.put("middleName", name.getMiddleName());
			item.put("familyNamePrefix", name.getFamilyNamePrefix());
			item.put("familyName", name.getFamilyName());
			item.put("familyName2", name.getFamilyName2());
			item.put("familyNameSuffix", name.getFamilyNameSuffix());
			item.put("degree", name.getDegree());
		}
		// Copiamos todas las direcciones no anuladas, no solo la preferida.
		// Los campos address1..15 son los del modelo; su etiqueta depende de la instalación.
		ArrayNode addresses = data.putArray("addresses");
		for (PersonAddress address : patient.getAddresses()) {
			if (Boolean.TRUE.equals(address.getVoided())) {
				continue;
			}
			ObjectNode item = addresses.addObject();
			item.put("uuid", required(address.getUuid(), "dirección"));
			item.put("preferred", Boolean.TRUE.equals(address.getPreferred()));
			item.put("address1", address.getAddress1());
			item.put("address2", address.getAddress2());
			item.put("address3", address.getAddress3());
			item.put("address4", address.getAddress4());
			item.put("address5", address.getAddress5());
			item.put("address6", address.getAddress6());
			item.put("address7", address.getAddress7());
			item.put("address8", address.getAddress8());
			item.put("address9", address.getAddress9());
			item.put("address10", address.getAddress10());
			item.put("address11", address.getAddress11());
			item.put("address12", address.getAddress12());
			item.put("address13", address.getAddress13());
			item.put("address14", address.getAddress14());
			item.put("address15", address.getAddress15());
			item.put("cityVillage", address.getCityVillage());
			item.put("countyDistrict", address.getCountyDistrict());
			item.put("stateProvince", address.getStateProvince());
			item.put("country", address.getCountry());
			item.put("postalCode", address.getPostalCode());
			item.put("latitude", address.getLatitude());
			item.put("longitude", address.getLongitude());
			item.put("startDate", instant(address.getStartDate()));
			item.put("endDate", instant(address.getEndDate()));
		}
		ArrayNode identifiers = data.putArray("identifiers");
		for (PatientIdentifier identifier : patient.getIdentifiers()) {
			if (Boolean.TRUE.equals(identifier.getVoided())) {
				continue;
			}
			ObjectNode item = identifiers.addObject();
			item.put("uuid", required(identifier.getUuid(), "identificador del paciente"));
			item.put("identifier", identifier.getIdentifier());
			item.put("preferred", Boolean.TRUE.equals(identifier.getPreferred()));
			item.put(
			    "identifierTypeUuid",
			    required(identifier.getIdentifierType() == null ? null : identifier.getIdentifierType().getUuid(),
			        "tipo de identificador"));
			item.put("locationUuid",
			    identifier.getLocation() == null ? null : required(identifier.getLocation().getUuid(), "ubicación"));
		}
		try {
			return mapper.writeValueAsString(event);
		}
		catch (JsonProcessingException failure) {
			// No incluir los datos personales del paciente en el mensaje de error.
			throw new APIException("No se pudo construir el JSON de creación del paciente", failure);
		}
	}
	
	private String required(String uuid, String reference) {
		if (uuid == null || uuid.trim().isEmpty()) {
			throw new APIException("Falta el UUID de la referencia: " + reference);
		}
		return uuid;
	}
	
	private String dateOnly(Date date) {
		return date == null ? null : new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date);
	}
	
	private String instant(Date date) {
		return date == null ? null : java.time.Instant.ofEpochMilli(date.getTime()).toString();
	}
}

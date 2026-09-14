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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;

/** Contrato explícito: valida el mensaje y reconstruye entidades, sin deserialización polimórfica. */
public final class PatientIncomingEvent {
	
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
	        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	
	public final JsonNode root;
	
	public final String origin;
	
	public final String eventUuid;
	
	public final String patientUuid;
	
	public final long sequence;
	
	public final Date occurredAt;
	
	public final String json;
	
	public PatientIncomingEvent(String json) {
		if (json == null || json.length() > 1000000) {
			throw invalid();
		}
		try {
			root = MAPPER.readTree(json);
		}
		catch (Exception e) {
			throw invalid();
		}
		fields(root, "schemaVersion,eventUuid,originNodeUuid,entityType,entitySequence,operation,occurredAt,payload");
		JsonNode version = root.get("schemaVersion");
		if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()
		        || (version.intValue() != 1 && version.intValue() != 2) || !"PATIENT".equals(text(root, "entityType", true))
		        || !"CREATE".equals(text(root, "operation", true))) {
			throw invalid();
		}
		origin = uuid(text(root, "originNodeUuid", true));
		eventUuid = uuid(text(root, "eventUuid", true));
		JsonNode seq = root.get("entitySequence");
		if (seq == null || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue() < 1) {
			throw invalid();
		}
		sequence = seq.longValue();
		occurredAt = instant(text(root, "occurredAt", true));
		fields(
		    root.get("payload"),
		    "patientUuid,gender,birthdate,birthdateEstimated,dead,deathDate,deathdateEstimated,causeOfDeathUuid,causeOfDeathNonCoded,names,identifiers,addresses");
		patientUuid = reference(text(root.get("payload"), "patientUuid", true));
		if (version.intValue() == 2) {
			array(root.get("payload"), "addresses", false);
		}
		this.json = json;
	}
	
	public boolean sameContent(String other) {
		if (other == null) {
			return false;
		}
		try {
			return root.equals(MAPPER.readTree(other));
		}
		catch (Exception e) {
			return false;
		}
	}
	
	public Patient toPatient() {
        JsonNode data = root.get("payload");
        Patient patient = new Patient();
        patient.setUuid(patientUuid);
        patient.setGender(text(data, "gender", true));
        String birthdate = text(data, "birthdate", false);
        if (birthdate != null) {
            try { patient.setBirthdate(java.sql.Date.valueOf(LocalDate.parse(birthdate))); }
            catch (Exception e) { throw invalid(); }
        }
        patient.setBirthdateEstimated(flag(data, "birthdateEstimated"));
        patient.setDead(flag(data, "dead"));
        patient.setDeathDate(instant(text(data, "deathDate", false)));
        patient.setDeathdateEstimated(flag(data, "deathdateEstimated"));
        patient.setCauseOfDeathNonCoded(text(data, "causeOfDeathNonCoded", false));
        String cause = text(data, "causeOfDeathUuid", false);
        if (cause != null) {
            Concept concept = Context.getConceptService().getConceptByUuid(reference(cause));
            if (concept == null || Boolean.TRUE.equals(concept.getRetired())) { throw dependency("causa de muerte"); }
            patient.setCauseOfDeath(concept);
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode item : array(data, "names", true)) {
            fields(item, "uuid,preferred,prefix,givenName,middleName,familyNamePrefix,familyName,familyName2,familyNameSuffix,degree");
            PersonName name = new PersonName();
            name.setUuid(unique(item, seen));
            if (Context.getPersonService().getPersonNameByUuid(name.getUuid()) != null) { throw conflict(); }
            name.setPreferred(flag(item, "preferred"));
            name.setPrefix(text(item, "prefix", false));
            name.setGivenName(text(item, "givenName", false));
            name.setMiddleName(text(item, "middleName", false));
            name.setFamilyNamePrefix(text(item, "familyNamePrefix", false));
            name.setFamilyName(text(item, "familyName", false));
            name.setFamilyName2(text(item, "familyName2", false));
            name.setFamilyNameSuffix(text(item, "familyNameSuffix", false));
            name.setDegree(text(item, "degree", false));
            patient.addName(name);
        }
        seen.clear();
        for (JsonNode item : array(data, "identifiers", true)) {
            fields(item, "uuid,identifier,preferred,identifierTypeUuid,locationUuid");
            PatientIdentifier identifier = new PatientIdentifier();
            identifier.setUuid(unique(item, seen));
            if (Context.getPatientService().getPatientIdentifierByUuid(identifier.getUuid()) != null) { throw conflict(); }
            identifier.setIdentifier(text(item, "identifier", true));
            identifier.setPreferred(flag(item, "preferred"));
            PatientIdentifierType type = Context.getPatientService()
                    .getPatientIdentifierTypeByUuid(reference(text(item, "identifierTypeUuid", true)));
            if (type == null || Boolean.TRUE.equals(type.getRetired())) { throw dependency("tipo de identificador"); }
            identifier.setIdentifierType(type);
            String locationUuid = text(item, "locationUuid", false);
            if (locationUuid != null) {
                Location location = Context.getLocationService().getLocationByUuid(reference(locationUuid));
                if (location == null || Boolean.TRUE.equals(location.getRetired())) { throw dependency("ubicación"); }
                identifier.setLocation(location);
            }
            patient.addIdentifier(identifier);
        }
        seen.clear();
        if (data.has("addresses")) {
            for (JsonNode item : array(data, "addresses", false)) {
                fields(item, "uuid,preferred,address1,address2,address3,address4,address5,address6,address7,address8,address9,address10,address11,address12,address13,address14,address15,cityVillage,countyDistrict,stateProvince,country,postalCode,latitude,longitude,startDate,endDate");
                PersonAddress address = new PersonAddress();
                address.setUuid(unique(item, seen));
                if (Context.getPersonService().getPersonAddressByUuid(address.getUuid()) != null) { throw conflict(); }
                address.setPreferred(flag(item, "preferred"));
                address.setAddress1(text(item, "address1", false));
                address.setAddress2(text(item, "address2", false));
                address.setAddress3(text(item, "address3", false));
                address.setAddress4(text(item, "address4", false));
                address.setAddress5(text(item, "address5", false));
                address.setAddress6(text(item, "address6", false));
                address.setAddress7(text(item, "address7", false));
                address.setAddress8(text(item, "address8", false));
                address.setAddress9(text(item, "address9", false));
                address.setAddress10(text(item, "address10", false));
                address.setAddress11(text(item, "address11", false));
                address.setAddress12(text(item, "address12", false));
                address.setAddress13(text(item, "address13", false));
                address.setAddress14(text(item, "address14", false));
                address.setAddress15(text(item, "address15", false));
                address.setCityVillage(text(item, "cityVillage", false));
                address.setCountyDistrict(text(item, "countyDistrict", false));
                address.setStateProvince(text(item, "stateProvince", false));
                address.setCountry(text(item, "country", false));
                address.setPostalCode(text(item, "postalCode", false));
                address.setLatitude(text(item, "latitude", false));
                address.setLongitude(text(item, "longitude", false));
                address.setStartDate(instant(text(item, "startDate", false)));
                address.setEndDate(instant(text(item, "endDate", false)));
                patient.addAddress(address);
                if (!patient.getAddresses().contains(address)) { throw invalid(); }
            }
        }
        return patient;
    }
	
	public static String uuid(String value) {
		if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
			throw invalid();
		}
		return value;
	}
	
	// Los UUID nativos son referencias opacas: algunos catálogos OpenMRS no usan formato RFC.
	// Los identificadores propios de nodo y evento sí se validan con uuid().
	private static String reference(String value) {
		if (value == null || value.trim().isEmpty() || value.length() > 38) {
			throw invalid();
		}
		return value;
	}
	
	private static String unique(JsonNode item, Set<String> seen) {
		String id = reference(text(item, "uuid", true));
		if (!seen.add(id)) {
			throw invalid();
		}
		return id;
	}
	
	private static void fields(JsonNode node, String allowed) {
        if (node == null || !node.isObject()) { throw invalid(); }
        Set<String> names = new HashSet<>(Arrays.asList(allowed.split(",")));
        Iterator<String> keys = node.fieldNames();
        while (keys.hasNext()) { if (!names.contains(keys.next())) { throw invalid(); } }
    }
	
	private static JsonNode array(JsonNode node, String key, boolean nonempty) {
		JsonNode value = node.get(key);
		if (value == null || !value.isArray() || value.size() > 100 || (nonempty && value.size() == 0)) {
			throw invalid();
		}
		return value;
	}
	
	private static String text(JsonNode node, String key, boolean required) {
		JsonNode value = node.get(key);
		if (value == null || value.isNull()) {
			if (required) {
				throw invalid();
			}
			return null;
		}
		if (!value.isTextual() || (required && value.asText().trim().isEmpty())) {
			throw invalid();
		}
		return value.asText();
	}
	
	private static boolean flag(JsonNode node, String key) {
		JsonNode value = node.get(key);
		if (value == null || !value.isBoolean()) {
			throw invalid();
		}
		return value.booleanValue();
	}
	
	private static Date instant(String value) {
		try {
			return value == null ? null : Date.from(Instant.parse(value));
		}
		catch (Exception e) {
			throw invalid();
		}
	}
	
	private static APIException invalid() {
		return new APIException("El evento de paciente no cumple el contrato admitido");
	}
	
	private static APIException dependency(String name) {
		return new APIException("Falta una referencia activa en el destino: " + name);
	}
	
	private static APIException conflict() {
		return new APIException("Un UUID recibido ya pertenece a un registro local; se requiere revisión");
	}
}

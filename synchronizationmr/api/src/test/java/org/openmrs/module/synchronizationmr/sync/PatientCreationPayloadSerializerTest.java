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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.PersonAddress;
import org.openmrs.api.APIException;
import static org.junit.jupiter.api.Assertions.*;

/** Verifica la copia de direcciones sin necesitar una base de datos. */
public class PatientCreationPayloadSerializerTest {
	
	private final PatientCreationPayloadSerializer serializer = new PatientCreationPayloadSerializer();
	
	private JsonNode payload(Patient patient) throws Exception {
		return new ObjectMapper().readTree(serializer.serialize(patient, "origen-prueba", 1, "evento-prueba", new Date(0)))
		        .get("payload");
	}
	
	@Test
	public void patientWithoutAddressesHasEmptyArray() throws Exception {
		JsonNode addresses = payload(new Patient()).get("addresses");
		assertTrue(addresses.isArray());
		assertEquals(0, addresses.size());
	}
	
	@Test
	public void preservesMultipleAddressesAndSkipsVoidedAddress() throws Exception {
		Patient patient = new Patient();
		PersonAddress preferred = new PersonAddress();
		preferred.setPreferred(true);
		preferred.setAddress1("Calle \"Río\" 12");
		preferred.setAddress15("Referencia adicional");
		preferred.setLatitude("-3.5");
		preferred.setLongitude("-73.2");
		preferred.setStartDate(new Date(0));
		patient.addAddress(preferred);
		PersonAddress other = new PersonAddress();
		other.setAddress1("Segunda dirección");
		other.setEndDate(new Date(86400000));
		patient.addAddress(other);
		PersonAddress voided = new PersonAddress();
		voided.setAddress1("Dirección anulada");
		voided.setVoided(true);
		patient.addAddress(voided);
		JsonNode addresses = payload(patient).get("addresses");
		assertEquals(2, addresses.size());
		for (JsonNode address : addresses) {
			assertFalse(address.has("personAddressId"));
			assertFalse(address.has("person"));
			if (preferred.getUuid().equals(address.get("uuid").asText())) {
				assertTrue(address.get("preferred").asBoolean());
				assertEquals("Calle \"Río\" 12", address.get("address1").asText());
				assertEquals("Referencia adicional", address.get("address15").asText());
				assertEquals("-3.5", address.get("latitude").asText());
				assertEquals("-73.2", address.get("longitude").asText());
				assertEquals("1970-01-01T00:00:00Z", address.get("startDate").asText());
				assertTrue(address.get("endDate").isNull());
				assertTrue(address.get("postalCode").isNull());
			} else {
				assertEquals(other.getUuid(), address.get("uuid").asText());
				assertFalse(address.get("preferred").asBoolean());
				assertEquals("1970-01-02T00:00:00Z", address.get("endDate").asText());
			}
		}
	}
	
	@Test
	public void rejectsAddressWithoutStableUuid() {
		Patient patient = new Patient();
		PersonAddress address = new PersonAddress();
		address.setAddress1("Dirección sin UUID");
		patient.addAddress(address);
        address.setUuid(null);
        assertThrows(APIException.class, () -> payload(patient));
    }
}

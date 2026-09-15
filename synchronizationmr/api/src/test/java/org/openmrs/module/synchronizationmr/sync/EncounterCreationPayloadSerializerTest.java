package org.openmrs.module.synchronizationmr.sync;

import java.util.Date;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openmrs.*;
import static org.junit.jupiter.api.Assertions.*;

public class EncounterCreationPayloadSerializerTest {
	
	private final EncounterCreationPayloadSerializer serializer = new EncounterCreationPayloadSerializer();
	
	private Encounter sample() {
		Encounter encounter = new Encounter();
		encounter.setPatient(new Patient());
		encounter.setEncounterType(new EncounterType());
		encounter.setEncounterDatetime(new Date(0));
		return encounter;
	}
	
	private Obs observation() {
		Obs obs = new Obs();
		obs.setConcept(new Concept());
		obs.setObsDatetime(new Date(0));
		return obs;
	}
	
	private JsonNode json(Encounter encounter) throws Exception {
		return new ObjectMapper().readTree(serializer.serialize(encounter, "origen", 7, "evento", new Date(0)));
	}
	
	@Test
	public void preservesGroupedValuesAndNativeReferences() throws Exception {
		Encounter encounter = sample();
		Obs group = observation();
		Obs numeric = observation();
		numeric.setValueNumeric(65.5);
		Obs coded = observation();
		coded.setValueCoded(new Concept());
		group.addGroupMember(numeric);
		group.addGroupMember(coded);
		encounter.addObs(group);
		JsonNode root = json(encounter);
		assertEquals("ENCOUNTER", root.path("entityType").asText());
		assertEquals(7, root.path("entitySequence").asInt());
		assertEquals(encounter.getPatient().getUuid(), root.path("payload").path("patientUuid").asText());
		JsonNode members = root.path("payload").path("obs").get(0).path("groupMembers");
		assertEquals(2, members.size());
		boolean foundNumeric = false, foundCoded = false;
		for (JsonNode member : members) {
			if (numeric.getUuid().equals(member.path("uuid").asText())) {
				assertEquals(65.5, member.path("valueNumeric").asDouble());
				foundNumeric = true;
			}
			if (coded.getUuid().equals(member.path("uuid").asText())) {
				assertEquals(coded.getValueCoded().getUuid(), member.path("valueCodedUuid").asText());
				foundCoded = true;
			}
		}
		assertTrue(foundNumeric && foundCoded);
	}
	
	@Test
	public void preservesTextDatesAndVoidedObservations() throws Exception {
		Encounter encounter = sample();
		Obs obs = observation();
		obs.setValueText("Texto ficticio");
		obs.setValueDatetime(new Date(0));
		obs.setVoided(true);
		obs.setVoidReason("Corrección ficticia");
		encounter.addObs(obs);
		JsonNode result = json(encounter).path("payload").path("obs").get(0);
		assertEquals("Texto ficticio", result.path("valueText").asText());
		assertEquals("1970-01-01T00:00:00Z", result.path("valueDatetime").asText());
		assertTrue(result.path("voided").asBoolean());
		assertTrue(result.path("valueNumeric").isNull());
	}
	
	@Test
	public void separatesOrderReferencesAndDoesNotClaimToIncludeComplexFiles() throws Exception {
		Encounter encounter = sample();
		Order order = new Order();
		encounter.addOrder(order);
		Obs obs = observation();
		obs.setValueComplex("archivo-ficticio");
		encounter.addObs(obs);
		JsonNode result = json(encounter).path("payload");
		assertEquals(order.getUuid(), result.path("orderUuids").get(0).asText());
		assertFalse(result.path("obs").get(0).path("complexDataIncluded").asBoolean());
		assertFalse(result.has("orders"));
	}
}

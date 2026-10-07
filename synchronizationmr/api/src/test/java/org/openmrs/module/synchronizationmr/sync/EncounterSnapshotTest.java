package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openmrs.*;
import org.openmrs.api.APIException;
import static org.junit.jupiter.api.Assertions.*;

public class EncounterSnapshotTest {
	
	private Encounter encounter() {
		Encounter encounter = new Encounter();
		encounter.setPatient(new Patient());
		encounter.setEncounterType(new EncounterType());
		encounter.setEncounterDatetime(new Date(0));
		return encounter;
	}
	
	private Obs observation(String text) {
		Obs obs = new Obs();
		obs.setConcept(new Concept());
		obs.setObsDatetime(new Date(0));
		obs.setValueText(text);
		return obs;
	}
	
	private Set<String> fields(String... fields) {
        return new LinkedHashSet<>(Arrays.asList(fields));
    }
	
	@Test
	public void detectsMetadataChangesIncludingExplicitRemoval() {
		Encounter encounter = encounter();
		encounter.setLocation(new Location());
		encounter.setForm(new Form());
		EncounterSnapshot before = EncounterSnapshot.capture(encounter);
		encounter.getEncounterDatetime().setTime(1000);
		encounter.setEncounterType(new EncounterType());
		encounter.setLocation(null);
		encounter.setForm(null);
		assertEquals(fields("encounterDatetime", "encounterTypeUuid", "locationUuid", "formUuid"),
		    before.changedFields(EncounterSnapshot.capture(encounter)));
		assertEquals("1970-01-01T00:00:00Z", before.toJson().path("encounterDatetime").asText());
	}
	
	@Test
	public void keepsNestedValuesIndependentOfLaterEdits() {
		Encounter encounter = encounter();
		Obs group = observation(null);
		Obs member = observation("Texto inicial ficticio");
		group.addGroupMember(member);
		encounter.addObs(group);
		EncounterSnapshot before = EncounterSnapshot.capture(encounter);
		member.setValueText("Texto corregido ficticio");
		member.setValueNumeric(5.0);
		assertEquals(fields("obs"), before.changedFields(EncounterSnapshot.capture(encounter)));
		JsonNode oldMember = before.toJson().path("obs").get(0).path("groupMembers").get(0);
		assertEquals("Texto inicial ficticio", oldMember.path("valueText").asText());
		assertTrue(oldMember.path("valueNumeric").isNull());
	}
	
	@Test
    public void ignoresIterationOrderAtEveryObservationLevelAndForOrderReferences() {
        Encounter encounter = encounter();
        Obs group = observation(null), first = observation("Uno"), second = observation("Dos");
        Obs other = observation("Tres");
        group.addGroupMember(first);
        group.addGroupMember(second);
        encounter.addObs(group);
        encounter.addObs(other);
        Order firstOrder = new Order(), secondOrder = new Order();
        encounter.addOrder(firstOrder);
        encounter.addOrder(secondOrder);
        EncounterSnapshot before = EncounterSnapshot.capture(encounter);
        group.setGroupMembers(new LinkedHashSet<>(Arrays.asList(second, first)));
        encounter.setObs(new LinkedHashSet<>(Arrays.asList(other, group)));
        encounter.setOrders(new LinkedHashSet<>(Arrays.asList(secondOrder, firstOrder)));
        assertTrue(before.changedFields(EncounterSnapshot.capture(encounter)).isEmpty());
    }
	
	@Test
	public void detectsMovingObservationBetweenGroups() {
		Encounter encounter = encounter();
		Obs first = observation(null), second = observation(null), member = observation("Prueba");
		first.addGroupMember(member);
		encounter.addObs(first);
		encounter.addObs(second);
		EncounterSnapshot before = EncounterSnapshot.capture(encounter);
		first.removeGroupMember(member);
		second.addGroupMember(member);
		assertEquals(fields("obs"), before.changedFields(EncounterSnapshot.capture(encounter)));
	}
	
	@Test
	public void preservesVoidedOriginalAndCorrectionLink() {
		Encounter encounter = encounter();
		Obs original = observation("Anterior");
		encounter.addObs(original);
		EncounterSnapshot before = EncounterSnapshot.capture(encounter);
		original.setVoided(true);
		original.setVoidReason("CorrecciÃ³n ficticia");
		Obs correction = observation("Corregido");
		correction.setPreviousVersion(original);
		encounter.addObs(correction);
		EncounterSnapshot after = EncounterSnapshot.capture(encounter);
		assertEquals(fields("obs"), before.changedFields(after));
		assertEquals(2, after.toJson().path("obs").size());
		for (JsonNode obs : after.toJson().path("obs")) {
			if (obs.path("uuid").asText().equals(original.getUuid())) {
				assertTrue(obs.path("voided").asBoolean());
				assertEquals("Anterior", obs.path("valueText").asText());
			} else {
				assertEquals(original.getUuid(), obs.path("previousVersionUuid").asText());
				assertEquals("Corregido", obs.path("valueText").asText());
			}
		}
		assertFalse(before.toJson().path("obs").get(0).path("voided").asBoolean());
	}
	
	@Test
	public void detectsObservationAdditionAndRemoval() {
		Encounter encounter = encounter();
		EncounterSnapshot empty = EncounterSnapshot.capture(encounter);
		encounter.addObs(observation("Nuevo"));
		EncounterSnapshot withObservation = EncounterSnapshot.capture(encounter);
		assertEquals(fields("obs"), empty.changedFields(withObservation));
		encounter.setObs(Collections.emptySet());
		assertEquals(fields("obs"), withObservation.changedFields(EncounterSnapshot.capture(encounter)));
	}
	
	@Test
    public void detectsProviderRoleChangeAndRemoval() {
        Encounter encounter = encounter();
        EncounterProvider provider = new EncounterProvider();
        provider.setProvider(new Provider());
        provider.setEncounterRole(new EncounterRole());
        encounter.setEncounterProviders(new LinkedHashSet<>(Arrays.asList(provider)));
        EncounterSnapshot before = EncounterSnapshot.capture(encounter);
        provider.setEncounterRole(new EncounterRole());
        EncounterSnapshot changed = EncounterSnapshot.capture(encounter);
        assertEquals(fields("encounterProviders"), before.changedFields(changed));
        provider.setVoided(true);
        assertEquals(fields("encounterProviders"), changed.changedFields(EncounterSnapshot.capture(encounter)));
    }
	
	@Test
	public void distinguishesVisitChangesFromEncounterAndObservationChanges() {
		Encounter encounter = encounter();
		Visit visit = new Visit();
		visit.setPatient(encounter.getPatient());
		visit.setVisitType(new VisitType());
		visit.setStartDatetime(new Date(0));
		encounter.setVisit(visit);
		EncounterSnapshot before = EncounterSnapshot.capture(encounter);
		visit.setStopDatetime(new Date(1000));
		assertEquals(fields("visit"), before.changedFields(EncounterSnapshot.capture(encounter)));
		encounter.setVisit(null);
		assertEquals(fields("visit", "visitUuid"), before.changedFields(EncounterSnapshot.capture(encounter)));
	}
	
	@Test
    public void returnsDefensiveJsonAndImmutableChangedFields() {
        Encounter encounter = encounter();
        encounter.addObs(observation("Conservar"));
        EncounterSnapshot before = EncounterSnapshot.capture(encounter);
        ObjectNode exposed = before.toJson();
        ((ObjectNode) exposed.path("obs").get(0)).put("valueText", "Alterado");
        exposed.removeAll();
        assertTrue(before.changedFields(EncounterSnapshot.capture(encounter)).isEmpty());
        encounter.setVoided(true);
        Set<String> changed = before.changedFields(EncounterSnapshot.capture(encounter));
        assertEquals(fields("voided"), changed);
        assertThrows(UnsupportedOperationException.class, () -> changed.add("formUuid"));
    }
	
	@Test
    public void rejectsMissingIdentityAndMovingEncounterToAnotherPatient() {
        assertThrows(APIException.class, () -> EncounterSnapshot.capture(null));
        Encounter encounter = encounter();
        EncounterSnapshot before = EncounterSnapshot.capture(encounter);
        assertThrows(APIException.class, () -> before.changedFields(null));
        encounter.setPatient(new Patient());
        assertThrows(APIException.class, () -> before.changedFields(EncounterSnapshot.capture(encounter)));
        assertThrows(APIException.class, () -> before.changedFields(EncounterSnapshot.capture(encounter())));
        encounter.setUuid(" ");
        assertThrows(APIException.class, () -> EncounterSnapshot.capture(encounter));
    }
	
	@Test
    public void rejectsRepeatedObservationUuidEvenInDifferentGroups() {
        Encounter encounter = encounter();
        Obs firstGroup = observation(null), secondGroup = observation(null);
        Obs first = observation("Uno"), second = observation("Dos");
        second.setUuid(first.getUuid());
        firstGroup.addGroupMember(first);
        secondGroup.addGroupMember(second);
        encounter.addObs(firstGroup);
        encounter.addObs(secondGroup);
        assertThrows(APIException.class, () -> EncounterSnapshot.capture(encounter));
    }
	
	@Test
	public void keepsExistingCreationContract() throws Exception {
		Encounter encounter = encounter();
		encounter.addObs(observation("Prueba"));
		encounter.addOrder(new Order());
		EncounterCreationPayloadSerializer serializer = new EncounterCreationPayloadSerializer();
		ObjectNode payload = serializer.snapshot(encounter);
		JsonNode event = new ObjectMapper().readTree(serializer.serialize(encounter, "posta_a", 17, "evento", new Date(0)));
		assertEquals(3, event.path("schemaVersion").asInt());
		assertEquals("CREATE", event.path("operation").asText());
		assertEquals(17, event.path("entitySequence").asInt());
		assertEquals(payload, event.path("payload"));
		EncounterSnapshot.capture(encounter).toJson().removeAll();
		assertEquals(payload, serializer.snapshot(encounter));
	}
}

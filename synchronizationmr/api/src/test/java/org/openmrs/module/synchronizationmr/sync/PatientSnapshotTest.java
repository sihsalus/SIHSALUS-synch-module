package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PersonAddress;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.api.APIException;
import static org.junit.jupiter.api.Assertions.*;

public class PatientSnapshotTest {
	
	private Patient patient() {
		Patient patient = new Patient();
		patient.setGender("F");
		patient.setBirthdate(java.sql.Date.valueOf("1991-08-15"));
		patient.addName(new PersonName("Prueba", null, "Paciente"));
		PersonAddress address = new PersonAddress();
		address.setAddress1("Calle de prueba");
		patient.addAddress(address);
		PatientIdentifierType type = new PatientIdentifierType();
		type.setName("Identificador de prueba");
		patient.addIdentifier(new PatientIdentifier("PRUEBA-1", type, null));
		return patient;
	}
	
	@Test
	public void unchangedSaveAndLocalMetadataDoNotProduceClinicalDifferences() {
		Patient patient = patient();
		PatientSnapshot before = PatientSnapshot.capture(patient);
		patient.setPatientId(42);
		patient.setDateChanged(new Date());
		assertTrue(before.changedFields(PatientSnapshot.capture(patient)).isEmpty());
	}
	
	@Test
    public void capturesScalarCorrectionsAndRetainsOldValues() {
        Patient patient = patient();
        PatientSnapshot before = PatientSnapshot.capture(patient);
        patient.setGender("M");
        patient.setBirthdate(java.sql.Date.valueOf("1991-08-16"));
        patient.setBirthdateEstimated(true);
        assertEquals(new HashSet<>(Arrays.asList("gender", "birthdate", "birthdateEstimated")),
            before.changedFields(PatientSnapshot.capture(patient)));
        assertEquals("F", before.toJson().path("gender").asText());
        assertEquals("1991-08-15", before.toJson().path("birthdate").asText());
    }
	
	@Test
	public void mutatingTheSameNameObjectDoesNotRewriteTheBaseline() {
		Patient patient = patient();
		PatientSnapshot before = PatientSnapshot.capture(patient);
		patient.getPersonName().setGivenName("Corregido");
		assertEquals(Collections.singleton("names"), before.changedFields(PatientSnapshot.capture(patient)));
		assertEquals("Prueba", before.toJson().path("names").get(0).path("givenName").asText());
	}
	
	@Test
	public void detectsClearedAddressValueWithoutTreatingItAsOmitted() {
		Patient patient = patient();
		PatientSnapshot before = PatientSnapshot.capture(patient);
		patient.getPersonAddress().setAddress1(null);
		PatientSnapshot after = PatientSnapshot.capture(patient);
		assertEquals(Collections.singleton("addresses"), before.changedFields(after));
		assertTrue(after.toJson().path("addresses").get(0).has("address1"));
		assertTrue(after.toJson().path("addresses").get(0).path("address1").isNull());
	}
	
	@Test
    public void comparesCollectionMembersByUuidRegardlessOfIterationOrder() {
        Patient patient = patient();
        PersonAddress second = new PersonAddress();
        second.setAddress1("Otra calle ficticia");
        patient.addAddress(second);
        Set<PersonAddress> forward = new TreeSet<>(Comparator.comparing(PersonAddress::getUuid));
        forward.addAll(patient.getAddresses());
        patient.setAddresses(forward);
        PatientSnapshot before = PatientSnapshot.capture(patient);
        Set<PersonAddress> reverse = new TreeSet<>(Comparator.comparing(PersonAddress::getUuid).reversed());
        reverse.addAll(forward);
        patient.setAddresses(reverse);
        assertTrue(before.changedFields(PatientSnapshot.capture(patient)).isEmpty());
    }
	
	@Test
	public void detectsAddedAndVoidedMembers() {
		Patient patient = patient();
		PatientSnapshot before = PatientSnapshot.capture(patient);
		PersonAddress second = new PersonAddress();
		second.setAddress1("Otra calle ficticia");
		patient.addAddress(second);
		PatientSnapshot added = PatientSnapshot.capture(patient);
		assertEquals(Collections.singleton("addresses"), before.changedFields(added));
		second.setVoided(true);
		assertEquals(Collections.singleton("addresses"), added.changedFields(PatientSnapshot.capture(patient)));
		assertTrue(before.changedFields(PatientSnapshot.capture(patient)).isEmpty());
	}
	
	@Test
    public void detectsPreferredNameAndIdentifierValueChanges() {
        Patient patient = patient();
        PatientSnapshot before = PatientSnapshot.capture(patient);
        patient.getPersonName().setPreferred(!patient.getPersonName().getPreferred());
        patient.getPatientIdentifier().setIdentifier("PRUEBA-2");
        assertEquals(new HashSet<>(Arrays.asList("names", "identifiers")),
            before.changedFields(PatientSnapshot.capture(patient)));
    }
	
	@Test
	public void detectsAttributeChangesAndVoidWithoutLeakingMutableReferences() {
		Patient patient = patient();
		PersonAttributeType type = new PersonAttributeType();
		type.setName("Telefono ficticio");
		type.setFormat("java.lang.String");
		PersonAttribute attribute = new PersonAttribute(type, "111");
		patient.addAttribute(attribute);
		PatientSnapshot before = PatientSnapshot.capture(patient);
		attribute.setValue("222");
		PatientSnapshot changed = PatientSnapshot.capture(patient);
		assertEquals(Collections.singleton("attributes"), before.changedFields(changed));
		assertEquals("111", before.toJson().path("attributes").get(0).path("value").asText());
		attribute.setVoided(true);
		assertEquals(Collections.singleton("attributes"), changed.changedFields(PatientSnapshot.capture(patient)));
	}
	
	@Test
    public void returnedJsonAndFieldSetCannotAlterTheSnapshot() {
        Patient patient = patient();
        PatientSnapshot before = PatientSnapshot.capture(patient);
        ObjectNode copy = before.toJson();
        copy.put("gender", "M");
        ((ObjectNode) copy.path("names").get(0)).put("givenName", "Otra copia");
        assertTrue(before.changedFields(PatientSnapshot.capture(patient)).isEmpty());
        patient.setGender("M");
        Set<String> fields = before.changedFields(PatientSnapshot.capture(patient));
        assertThrows(UnsupportedOperationException.class, () -> fields.add("names"));
    }
	
	@Test
    public void rejectsDifferentPatientAndVoidedPatientInsteadOfTreatingThemAsAnEdit() {
        PatientSnapshot before = PatientSnapshot.capture(patient());
        assertThrows(APIException.class, () -> before.changedFields(PatientSnapshot.capture(patient())));
        assertThrows(APIException.class, () -> before.changedFields(null));
        assertThrows(APIException.class, () -> PatientSnapshot.capture(null));
        Patient voided = patient();
        voided.setVoided(true);
        assertThrows(APIException.class, () -> PatientSnapshot.capture(voided));
    }
	
	@Test
    public void rejectsDuplicateMemberUuidsRatherThanSilentlyDiscardingOne() {
        Patient patient = patient();
        PersonAddress second = new PersonAddress();
        second.setAddress1("Otra calle ficticia");
        second.setUuid(patient.getPersonAddress().getUuid());
		// Conserva ambos elementos malformados: la comparación de igualdad de OpenMRS agrupa los UUID iguales.
		Set<PersonAddress> addresses = new TreeSet<>(Comparator.comparing(PersonAddress::getAddress1));
        addresses.add(patient.getPersonAddress());
        addresses.add(second);
		patient.setAddresses(addresses);
		assertEquals(2, patient.getAddresses().size());
        assertThrows(APIException.class, () -> PatientSnapshot.capture(patient));
    }
	
	@Test
	public void creationContractKeepsItsEnvelopeAndSameClinicalFields() throws Exception {
		Patient patient = patient();
		String json = new PatientCreationPayloadSerializer().serialize(patient, "posta_a", 7,
		    "a788bf89-f758-4414-809c-cb292d932cc3", new Date(0));
		com.fasterxml.jackson.databind.JsonNode event = new ObjectMapper().readTree(json);
		assertEquals(4, event.path("schemaVersion").asInt());
		assertEquals("CREATE", event.path("operation").asText());
		assertEquals(7, event.path("entitySequence").asLong());
		assertEquals(PatientSnapshot.capture(patient).toJson(), event.path("payload"));
	}
}

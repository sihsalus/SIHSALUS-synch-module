package org.openmrs.module.synchronizationmr.api;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.EncounterService;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.advice.EncounterCreationAdvice;
import org.openmrs.module.synchronizationmr.sync.*;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class EncounterReceiveIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private EncounterCreationAdvice advice;
	
	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		advice = new EncounterCreationAdvice();
		Context.addAdvice(EncounterService.class, advice);
	}
	
	@AfterEach
	public void cleanup() {
		Context.removeAdvice(EncounterService.class, advice);
	}
	
	private EncounterReceiveService receiver() {
		return Context.getService(EncounterReceiveService.class);
	}
	
	private Encounter sample() {
		Encounter encounter = new Encounter();
		encounter.setPatient(Context.getPatientService().getPatient(2));
		encounter.setEncounterType(Context.getEncounterService().getEncounterType(1));
		encounter.setLocation(Context.getLocationService().getLocation(1));
		encounter.setEncounterDatetime(new Date());
		return encounter;
	}
	
	private String event(Encounter encounter, String origin, long seq) {
		return new EncounterCreationPayloadSerializer().serialize(encounter, origin, seq, UUID.randomUUID().toString(),
		    new Date());
	}
	
	private Obs obs(Encounter encounter, int concept) {
		Obs obs = new Obs();
		obs.setPerson(encounter.getPatient());
		obs.setEncounter(encounter);
		obs.setConcept(Context.getConceptService().getConcept(concept));
		obs.setObsDatetime(encounter.getEncounterDatetime());
		return obs;
	}
	
	private long counter() throws Exception {
        try (java.sql.Statement s = getConnection().createStatement();
                java.sql.ResultSet r = s.executeQuery("select encounter_sequence from synchronizationmr_local_node")) { r.next(); return r.getLong(1); }
    }
	
	@Test public void receivesGroupedObservationsAndRetainsOriginalEventWithoutLocalRecapture() throws Exception {
        Encounter source = sample();
        Obs group = obs(source, 23), numeric = obs(source, 5089), text = obs(source, 19);
        numeric.setValueNumeric(62.5); text.setValueText("Texto ficticio");
        group.addGroupMember(numeric); group.addGroupMember(text); source.addObs(group);
        String origin = "posta_" + UUID.randomUUID(), json = event(source, origin, 1);
        long before = counter();
        assertEquals(1, receiver().receiveEncounter(json));
        Context.flushSession(); Context.clearSession();
        Encounter saved = Context.getEncounterService().getEncounterByUuid(source.getUuid());
        assertEquals(2, saved.getPatient().getPatientId().intValue());
        assertEquals(62.5, Context.getObsService().getObsByUuid(numeric.getUuid()).getValueNumeric());
        assertEquals("Texto ficticio", Context.getObsService().getObsByUuid(text.getUuid()).getValueText());
        assertEquals(group.getUuid(), Context.getObsService().getObsByUuid(text.getUuid()).getObsGroup().getUuid());
        assertEquals(before, counter());
        assertEquals(1, receiver().receiveEncounter(json));
        try (java.sql.PreparedStatement s = getConnection().prepareStatement("select origin_server_id, payload_json from synchronizationmr_encounter_event where encounter_id = ?")) {
            s.setInt(1, saved.getEncounterId());
            try (java.sql.ResultSet r = s.executeQuery()) {
                assertTrue(r.next()); assertEquals(origin, r.getString(1)); assertEquals(json, r.getString(2)); assertFalse(r.next());
            }
        }
    }
	
	@Test public void missingPatientDoesNotCreateItOrConfirmEncounter() throws Exception {
        Encounter source = sample(); String origin = "posta_" + UUID.randomUUID();
        ObjectNode json = (ObjectNode) mapper.readTree(event(source, origin, 1));
        String missing = UUID.randomUUID().toString();
        ((ObjectNode) json.get("payload")).put("patientUuid", missing);
        assertThrows(APIException.class, () -> receiver().receiveEncounter(json.toString()));
        assertNull(Context.getPatientService().getPatientByUuid(missing));
        assertNull(Context.getEncounterService().getEncounterByUuid(source.getUuid()));
        assertEquals(0, receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void rejectsMissingConceptAndUnsupportedContentWithoutPartialSave() throws Exception {
        Encounter source = sample(); Obs observation = obs(source, 5089); observation.setValueNumeric(10.0); source.addObs(observation);
        String origin = "posta_" + UUID.randomUUID();
        ObjectNode json = (ObjectNode) mapper.readTree(event(source, origin, 1));
        ((ObjectNode) json.path("payload").path("obs").get(0)).put("conceptUuid", UUID.randomUUID().toString());
        final String missingConcept = json.toString();
        assertThrows(APIException.class, () -> receiver().receiveEncounter(missingConcept));
        json = (ObjectNode) mapper.readTree(event(source, origin, 1));
        ((ArrayNode) json.path("payload").path("unsupportedContent")).add("DIAGNOSES");
        final String unsupported = json.toString();
        assertThrows(APIException.class, () -> receiver().receiveEncounter(unsupported));
        assertNull(Context.getEncounterService().getEncounterByUuid(source.getUuid()));
        assertNull(Context.getObsService().getObsByUuid(observation.getUuid()));
        assertEquals(0, receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void rejectsGapsConflictingRetriesAndExistingEncounter() throws Exception {
        String origin = "posta_" + UUID.randomUUID(); Encounter source = sample(); String json = event(source, origin, 1);
        assertThrows(APIException.class, () -> receiver().receiveEncounter(event(sample(), origin, 2)));
        assertEquals(1, receiver().receiveEncounter(json));
        ObjectNode changed = (ObjectNode) mapper.readTree(json);
        ((ObjectNode) changed.get("payload")).put("encounterDatetime", "2020-01-01T00:00:00Z");
        assertThrows(APIException.class, () -> receiver().receiveEncounter(changed.toString()));
        assertThrows(APIException.class, () -> receiver().receiveEncounter(event(source, "other_" + UUID.randomUUID(), 1)));
        assertEquals(1, receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void databaseFailureRollsBackEncounterObservationsAndReceipt() throws Exception {
        TestTransaction.flagForCommit(); TestTransaction.end();
        try {
            String origin = "posta_" + UUID.randomUUID();
            ObjectNode first = (ObjectNode) mapper.readTree(event(sample(), origin, 1));
            receiver().receiveEncounter(first.toString());
            Encounter second = sample(); Obs observation = obs(second, 5089); observation.setValueNumeric(20.0); second.addObs(observation);
            Obs previous = obs(second, 5089); previous.setValueNumeric(18.0);
            previous.setVoided(true); previous.setVoidReason("Fictitious correction");
            observation.setPreviousVersion(previous); second.addObs(previous);
            ObjectNode duplicate = (ObjectNode) mapper.readTree(event(second, origin, 2)); duplicate.put("eventUuid", first.path("eventUuid").asText());
            assertThrows(RuntimeException.class, () -> receiver().receiveEncounter(duplicate.toString()));
            assertNull(Context.getEncounterService().getEncounterByUuid(second.getUuid()));
            assertNull(Context.getObsService().getObsByUuid(observation.getUuid()));
            assertNull(Context.getObsService().getObsByUuid(previous.getUuid()));
            assertEquals(1, receiver().getConfirmedEncounterSequence(origin));
            assertEquals(2, receiver().receiveEncounter(event(second, origin, 2)));
        } finally { TestTransaction.start(); }
    }
	
	@Test
	public void preservesHistoricalObservationVersionsInsideSnapshotAndOnRetry() throws Exception {
		Encounter source = sample();
		Obs old = obs(source, 5089), corrected = obs(source, 5089);
		old.setValueNumeric(60.0);
		old.setVoided(true);
		old.setVoidReason("Fictitious correction");
		corrected.setValueNumeric(62.5);
		corrected.setPreviousVersion(old);
		Obs group = obs(source, 23);
		group.addGroupMember(corrected);
		group.addGroupMember(old);
		source.addObs(group);
		String origin = "posta_" + UUID.randomUUID(), json = event(source, origin, 1);
		assertEquals(1, receiver().receiveEncounter(json));
		Context.flushSession();
		Context.clearSession();
		Obs saved = Context.getObsService().getObsByUuid(corrected.getUuid());
		assertEquals(old.getUuid(), saved.getPreviousVersion().getUuid());
		assertTrue(saved.getPreviousVersion().getVoided());
		assertEquals(60.0, saved.getPreviousVersion().getValueNumeric());
		assertEquals(62.5, saved.getValueNumeric());
		Integer id = saved.getObsId();
		assertEquals(1, receiver().receiveEncounter(json));
		Context.clearSession();
		assertEquals(id, Context.getObsService().getObsByUuid(corrected.getUuid()).getObsId());
	}
	
	@Test
	public void resolvesHistoricalVersionFromEarlierEncounterOfSamePatient() throws Exception {
		Encounter first = sample();
		Obs old = obs(first, 5089);
		old.setValueNumeric(10.0);
		old.setVoided(true);
		old.setVoidReason("Fictitious correction");
		first.addObs(old);
		String origin = "posta_" + UUID.randomUUID();
		receiver().receiveEncounter(event(first, origin, 1));
		Encounter next = sample();
		Obs corrected = obs(next, 5089);
		corrected.setValueNumeric(12.0);
		corrected.setPreviousVersion(old);
		next.addObs(corrected);
		receiver().receiveEncounter(event(next, origin, 2));
		Context.flushSession();
		Context.clearSession();
		assertEquals(old.getUuid(), Context.getObsService().getObsByUuid(corrected.getUuid()).getPreviousVersion().getUuid());
	}
	
	@Test public void rejectsMissingHistoricalVersionAndCyclesWithoutConfirming() throws Exception {
        Encounter source = sample(); Obs observation = obs(source, 5089); observation.setValueNumeric(10.0); source.addObs(observation);
        String origin = "posta_" + UUID.randomUUID();
        ObjectNode json = (ObjectNode) mapper.readTree(event(source, origin, 1));
        ObjectNode item = (ObjectNode) json.path("payload").path("obs").get(0);
        item.put("previousVersionUuid", UUID.randomUUID().toString());
        assertThrows(APIException.class, () -> receiver().receiveEncounter(json.toString()));
        item.put("previousVersionUuid", observation.getUuid());
        assertThrows(APIException.class, () -> receiver().receiveEncounter(json.toString()));
        assertNull(Context.getEncounterService().getEncounterByUuid(source.getUuid()));
        assertNull(Context.getObsService().getObsByUuid(observation.getUuid()));
        assertEquals(0, receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void rejectsPreviousObservationFromAnotherPatient() throws Exception {
        Encounter first = sample(); first.setPatient(Context.getPatientService().getPatient(6));
        Obs old = obs(first, 5089); old.setValueNumeric(10.0); first.addObs(old);
        String origin = "posta_" + UUID.randomUUID(); receiver().receiveEncounter(event(first, origin, 1));
        Encounter next = sample(); Obs corrected = obs(next, 5089); corrected.setValueNumeric(12.0);
        corrected.setPreviousVersion(old); next.addObs(corrected);
        assertThrows(APIException.class, () -> receiver().receiveEncounter(event(next, origin, 2)));
        assertNull(Context.getEncounterService().getEncounterByUuid(next.getUuid()));
        assertEquals(1, receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void rejectsCycleBetweenTwoVersionsBeforeSaving() throws Exception {
        Encounter source = sample(); Obs first = obs(source, 5089), second = obs(source, 5089);
        first.setValueNumeric(10.0); second.setValueNumeric(12.0);
        first.setPreviousVersion(second); second.setPreviousVersion(first);
        source.addObs(first); source.addObs(second);
        String origin = "posta_" + UUID.randomUUID();
        assertThrows(APIException.class, () -> receiver().receiveEncounter(event(source, origin, 1)));
        assertNull(Context.getEncounterService().getEncounterByUuid(source.getUuid()));
        assertEquals(0, receiver().getConfirmedEncounterSequence(origin));
    }
	
	public static class AssignVisitHandler extends org.openmrs.api.handler.BaseEncounterVisitHandler {
		
		public String getDisplayName(java.util.Locale locale) {
			return "Fictitious automatic visit";
		}
		
		public void beforeCreateEncounter(Encounter encounter) {
			Visit visit = new Visit();
			visit.setPatient(encounter.getPatient());
			visit.setVisitType(Context.getVisitService().getVisitType(1));
			visit.setStartDatetime(new Date(encounter.getEncounterDatetime().getTime() - 60000));
			encounter.setVisit(visit);
		}
	}
	
	private void enableAutomaticVisit() {
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(org.openmrs.util.OpenmrsConstants.GP_VISIT_ASSIGNMENT_HANDLER, AssignVisitHandler.class
		            .getName()));
	}
	
	private long visitCount() throws Exception {
        try (java.sql.Statement s = getConnection().createStatement();
             java.sql.ResultSet r = s.executeQuery("select count(*) from visit")) {
            r.next(); return r.getLong(1);
        }
    }
	
	@Test
	public void importWithoutVisitDoesNotCreateOneButLocalRegistrationStillDoes() throws Exception {
		enableAutomaticVisit();
		Encounter source = sample();
		long before = visitCount();
		String json = event(source, "posta_" + UUID.randomUUID(), 1);
		receiver().receiveEncounter(json);
		Context.flushSession();
		Context.clearSession();
		assertNull(Context.getEncounterService().getEncounterByUuid(source.getUuid()).getVisit());
		assertEquals(before, visitCount());
		assertEquals(1, receiver().receiveEncounter(json));
		Encounter local = Context.getEncounterService().saveEncounter(sample());
		Context.flushSession();
		assertNotNull(local.getVisit());
		assertEquals(before + 1, visitCount());
	}
	
	@Test
	public void importKeepsExplicitVisitInsteadOfReplacingItWithLocalAutomaticVisit() throws Exception {
		enableAutomaticVisit();
		Encounter local = Context.getEncounterService().saveEncounter(sample());
		Context.flushSession();
		String visitUuid = local.getVisit().getUuid();
		Encounter source = sample();
		source.setVisit(local.getVisit());
		long before = visitCount();
		receiver().receiveEncounter(event(source, "posta_" + UUID.randomUUID(), 1));
		Context.flushSession();
		Context.clearSession();
		assertEquals(visitUuid, Context.getEncounterService().getEncounterByUuid(source.getUuid()).getVisit().getUuid());
		assertEquals(before, visitCount());
	}
	
	@Test public void missingVisitCannotBeReplacedByAnAutomaticallyCreatedVisit() throws Exception {
        enableAutomaticVisit(); Encounter source = sample(); String origin = "posta_" + UUID.randomUUID();
        ObjectNode json = (ObjectNode) mapper.readTree(event(source, origin, 1));
        ((ObjectNode)json.get("payload")).put("visitUuid", UUID.randomUUID().toString());
        long before = visitCount();
        assertThrows(APIException.class, () -> receiver().receiveEncounter(json.toString()));
        assertNull(Context.getEncounterService().getEncounterByUuid(source.getUuid()));
        assertEquals(before, visitCount()); assertEquals(0, receiver().getConfirmedEncounterSequence(origin));
    }
}

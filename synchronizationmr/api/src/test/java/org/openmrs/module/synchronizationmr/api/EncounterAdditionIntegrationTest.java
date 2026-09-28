package org.openmrs.module.synchronizationmr.api;

import java.util.*;
import java.sql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.EncounterService;
import org.openmrs.api.ObsService;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.advice.*;
import org.openmrs.module.synchronizationmr.sync.*;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

/** Real OpenMRS saves, transactions, grouped observations and immutable event transport. */
public class EncounterAdditionIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private final EncounterCreationAdvice encounterAdvice = new EncounterCreationAdvice();
	
	private final ObservationAdditionAdvice obsAdvice = new ObservationAdditionAdvice();
	
	private final EncounterCreationPayloadSerializer serializer = new EncounterCreationPayloadSerializer();
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private long beforeCreation, beforeAddition, beforeSequence;
	
	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		Context.addAdvice(EncounterService.class, encounterAdvice);
		Context.addAdvice(ObsService.class, obsAdvice);
		beforeCreation = count("select count(*) from synchronizationmr_encounter_event");
		beforeAddition = count("select count(*) from synchronizationmr_encounter_addition");
		beforeSequence = sync().getHighestEncounterSequence("testServer_1");
	}
	
	@AfterEach
	public void cleanup() {
		Context.removeAdvice(EncounterService.class, encounterAdvice);
		Context.removeAdvice(ObsService.class, obsAdvice);
	}
	
	private Encounter sample() {
		Encounter e = new Encounter();
		e.setPatient(Context.getPatientService().getPatient(2));
		e.setEncounterType(Context.getEncounterService().getEncounterType(1));
		e.setLocation(Context.getLocationService().getLocation(1));
		e.setEncounterDatetime(new java.util.Date());
		return e;
	}
	
	private Obs obs(Encounter e, int concept, Double value) {
		Obs o = new Obs();
		o.setPerson(e.getPatient());
		o.setEncounter(e);
		o.setConcept(Context.getConceptService().getConcept(concept));
		o.setObsDatetime(e.getEncounterDatetime());
		o.setValueNumeric(value);
		return o;
	}
	
	private long count(String sql) throws Exception {
        try (Statement s=getConnection().createStatement(); ResultSet r=s.executeQuery(sql)) {r.next();return r.getLong(1);}
    }
	
	private EncounterSyncService sync() {
		return Context.getService(EncounterSyncService.class);
	}
	
	private EncounterReceiveService receiver() {
		return Context.getService(EncounterReceiveService.class);
	}
	
	private String creation(Encounter e, String origin) {
		return serializer.serialize(e, origin, 1, UUID.randomUUID().toString(), new java.util.Date());
	}
	
	private void ids(Obs o, Set<String> ids) {
		ids.add(o.getUuid());
		if (o.getGroupMembers(true) != null)
			for (Obs child : o.getGroupMembers(true))
				ids(child, ids);
	}
	
	private String addition(Encounter e,String origin,long seq,Obs... observations) {
        Set<String> ids=new HashSet<>();for(Obs o:observations) ids(o,ids);
        return serializer.serializeAddition(e,Arrays.asList(observations),ids,origin,seq,UUID.randomUUID().toString(),new java.util.Date());
    }
	
	@Test
	public void encounterSaveCapturesGroupOnceAndKeepsCreationImmutable() throws Exception {
		Encounter e = Context.getEncounterService().saveEncounter(sample());
		String original = sync().getEncounterEventsAfter("testServer_1", beforeSequence, 100).get(0).getPayloadJson();
		Obs group = obs(e, 23, null), result = obs(e, 5089, 62.5);
		group.addGroupMember(result);
		e.addObs(group);
		Context.getEncounterService().saveEncounter(e);
		Context.getEncounterService().saveEncounter(e);
		sync().recordAddedObservations(e);
		assertEquals(beforeCreation + 1, count("select count(*) from synchronizationmr_encounter_event"));
		assertEquals(beforeAddition + 1, count("select count(*) from synchronizationmr_encounter_addition"));
		List<EncounterSyncEvent> events = sync().getEncounterEventsAfter("testServer_1", beforeSequence, 100);
		assertEquals(2, events.size());
		assertEquals(original, events.get(0).getPayloadJson());
		assertEquals("ADD_OBS", mapper.readTree(events.get(1).getPayloadJson()).path("operation").asText());
		assertEquals(beforeSequence + 2, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	private void saveHistorical(Obs value) {
		Context.removeAdvice(ObsService.class, obsAdvice);
		try {
			Context.getObsService().saveObs(value, null);
			Context.flushSession();
		}
		finally {
			Context.addAdvice(ObsService.class, obsAdvice);
		}
	}
	
	@Test
	public void preparesHistoricalResultsWithoutChangingClinicalRowsAndRescansWithoutDuplicates() throws Exception {
		Encounter e = Context.getEncounterService().saveEncounter(sample());
		String original = sync().getEncounterEventsAfter("testServer_1", beforeSequence, 1).get(0).getPayloadJson();
		Obs group = obs(e, 23, null), result = obs(e, 5089, 62.5);
		group.addGroupMember(result);
		saveHistorical(group);
		long clinicalCount = count("select count(*) from obs");
		java.util.Date clinicalDate = result.getObsDatetime();
		assertEquals(beforeAddition, count("select count(*) from synchronizationmr_encounter_addition"));
		ObservationPreparationBatch batch = sync().prepareExistingObservations(e.getEncounterId() - 1, 1);
		assertEquals(1, batch.getScanned());
		assertEquals(1, batch.getPublished());
		assertEquals(e.getEncounterId().intValue(), batch.getLastEncounterId());
		assertTrue(batch.isComplete());
		String json = sync().getEncounterEventsAfter("testServer_1", beforeSequence + 1, 1).get(0).getPayloadJson();
		com.fasterxml.jackson.databind.JsonNode item = mapper.readTree(json).path("payload").path("obs").get(0);
		assertEquals(group.getUuid(), item.path("uuid").asText());
		assertEquals(result.getUuid(), item.path("groupMembers").get(0).path("uuid").asText());
		assertEquals(62.5, item.path("groupMembers").get(0).path("valueNumeric").asDouble());
		assertEquals(0, sync().prepareExistingObservations(e.getEncounterId() - 1, 1).getPublished());
		assertEquals(beforeSequence + 2, sync().getHighestEncounterSequence("testServer_1"));
		assertEquals(original, sync().getEncounterEventsAfter("testServer_1", beforeSequence, 1).get(0).getPayloadJson());
		assertEquals(clinicalCount, count("select count(*) from obs"));
		Context.clearSession();
		assertEquals(clinicalDate, Context.getObsService().getObsByUuid(result.getUuid()).getObsDatetime());
		assertEquals(62.5, Context.getObsService().getObsByUuid(result.getUuid()).getValueNumeric());
	}
	
	@Test
	public void preparationAdvancesPastEmptyEncountersAndHonorsBatchSize() throws Exception {
		Encounter empty = Context.getEncounterService().saveEncounter(sample());
		Encounter withResult = Context.getEncounterService().saveEncounter(sample());
		saveHistorical(obs(withResult, 5089, 63.0));
		ObservationPreparationBatch first = sync().prepareExistingObservations(empty.getEncounterId() - 1, 1);
		assertEquals(1, first.getScanned());
		assertEquals(0, first.getPublished());
		assertFalse(first.isComplete());
		assertEquals(empty.getEncounterId().intValue(), first.getLastEncounterId());
		ObservationPreparationBatch second = sync().prepareExistingObservations(first.getLastEncounterId(), 1);
		assertEquals(1, second.getPublished());
		assertTrue(second.isComplete());
		assertEquals(withResult.getEncounterId().intValue(), second.getLastEncounterId());
		ObservationPreparationBatch end = sync().prepareExistingObservations(second.getLastEncounterId(), 1);
		assertEquals(0, end.getScanned());
		assertTrue(end.isComplete());
	}
	
	@Test
	public void preparationRollbackKeepsHistoricalResultsAndCanRetry() throws Exception {
		Encounter e = Context.getEncounterService().saveEncounter(sample());
		Obs result = obs(e, 5089, 64.0);
		saveHistorical(result);
		int cursor = e.getEncounterId() - 1;
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		assertEquals(1, sync().prepareExistingObservations(cursor, 1).getPublished());
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertEquals(beforeAddition, count("select count(*) from synchronizationmr_encounter_addition"));
		assertEquals(beforeSequence + 1, sync().getHighestEncounterSequence("testServer_1"));
		assertEquals(64.0, Context.getObsService().getObsByUuid(result.getUuid()).getValueNumeric());
		assertEquals(1, sync().prepareExistingObservations(cursor, 1).getPublished());
		// Commit the retry so future tests do not inherit an unpublished fixture.
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
	}
	
	@Test
    public void preparationRejectsInvalidCursorAndBounds() {
        assertThrows(APIException.class, () -> sync().prepareExistingObservations(-1, 1));
        assertThrows(APIException.class, () -> sync().prepareExistingObservations(0, 0));
        assertThrows(APIException.class, () -> sync().prepareExistingObservations(0, 101));
    }
	
	@Test
	public void directObsSaveCapturesCompleteGroupAndSecondAddition() throws Exception {
		Encounter e = Context.getEncounterService().saveEncounter(sample());
		Obs group = obs(e, 23, null), first = obs(e, 5089, 61.0);
		group.addGroupMember(first);
		Context.getObsService().saveObs(group, null);
		assertEquals(beforeAddition + 1, count("select count(*) from synchronizationmr_encounter_addition"));
		Context.getObsService().saveObs(obs(e, 5089, 62.0), null);
		assertEquals(beforeAddition + 2, count("select count(*) from synchronizationmr_encounter_addition"));
		assertEquals(3, sync().getEncounterEventsAfter("testServer_1", beforeSequence, 100).size());
	}
	
	@Test
	public void initialGroupRemainsInCreationAndLaterChildReferencesExistingGroup() throws Exception {
		Encounter e = sample();
		Obs group = obs(e, 23, null), first = obs(e, 5089, 61.0);
		group.addGroupMember(first);
		e.addObs(group);
		Context.getEncounterService().saveEncounter(e);
		assertEquals(beforeAddition, count("select count(*) from synchronizationmr_encounter_addition"));
		Obs child = obs(e, 5089, 62.0);
		group.addGroupMember(child);
		Context.getObsService().saveObs(child, null);
		String json = sync().getEncounterEventsAfter("testServer_1", beforeSequence + 1, 1).get(0).getPayloadJson();
		com.fasterxml.jackson.databind.JsonNode items = mapper.readTree(json).path("payload").path("obs");
		assertEquals(1, items.size());
		assertEquals(child.getUuid(), items.get(0).path("uuid").asText());
		assertEquals(group.getUuid(), items.get(0).path("parentGroupUuid").asText());
		sync().recordAddedObservations(e);
		assertEquals(beforeAddition + 1, count("select count(*) from synchronizationmr_encounter_addition"));
	}
	
	@Test
	public void receivesGroupWithOrderLinkAndRetriesWithoutEchoOrDuplicates() throws Exception {
		Encounter source = sample();
		String origin = "posta_results";
		receiver().receiveEncounter(creation(source, origin));
		org.openmrs.Order order = new org.openmrs.Order();
		order.setPatient(source.getPatient());
		order.setEncounter(Context.getEncounterService().getEncounterByUuid(source.getUuid()));
		order.setConcept(Context.getConceptService().getConcept(5089));
		order.setOrderType(Context.getOrderService().getOrderType(17));
		order.setOrderer(Context.getProviderService().getProvider(1));
		order.setCareSetting(Context.getOrderService().getCareSetting(1));
		order.setDateActivated(source.getEncounterDatetime());
		Context.getService(OrderReceiveService.class).receiveOrder(
		    new OrderCreationPayloadSerializer().serialize(order, origin, 1, UUID.randomUUID().toString(),
		        new java.util.Date()));
		Obs group = obs(source, 23, null), numeric = obs(source, 5089, 62.5);
		numeric.setOrder(order);
		group.addGroupMember(numeric);
		String json = addition(source, origin, 2, group);
		long before = count("select encounter_sequence from synchronizationmr_local_node");
		assertEquals(2, receiver().receiveEncounter(json));
		assertEquals(2, receiver().receiveEncounter(json));
		Context.flushSession();
		Context.clearSession();
		Obs saved = Context.getObsService().getObsByUuid(numeric.getUuid());
		assertEquals(62.5, saved.getValueNumeric());
		assertEquals(group.getUuid(), saved.getObsGroup().getUuid());
		assertEquals(order.getUuid(), saved.getOrder().getUuid());
		assertEquals(source.getUuid(), saved.getEncounter().getUuid());
		assertEquals(before, count("select encounter_sequence from synchronizationmr_local_node"));
		assertEquals(beforeAddition + 1, count("select count(*) from synchronizationmr_encounter_addition"));
		assertEquals(0, count("select count(*) from synchronizationmr_order_link"));
		assertEquals(json, sync().getEncounterEventsAfter(origin, 1, 1).get(0).getPayloadJson());
	}
	
	@Test
	public void appendsToExistingGroupWithoutReplacingItsMembers() throws Exception {
		Encounter source = sample();
		Obs group = obs(source, 23, null), first = obs(source, 5089, 61.0);
		group.addGroupMember(first);
		source.addObs(group);
		receiver().receiveEncounter(creation(source, "posta_group"));
		Obs second = obs(source, 5089, 62.0);
		second.setObsGroup(group);
		receiver().receiveEncounter(addition(source, "posta_group", 2, second));
		Context.flushSession();
		Context.clearSession();
		assertEquals(2, Context.getObsService().getObsByUuid(group.getUuid()).getGroupMembers().size());
		assertEquals(61.0, Context.getObsService().getObsByUuid(first.getUuid()).getValueNumeric());
	}
	
	@Test public void rejectsMissingEncounterWrongPatientGapsAndConflictingRetry() throws Exception {
        Encounter source=sample();Obs value=obs(source,5089,62.0);
        String json=addition(source,"posta_invalid",2,value);
        assertThrows(APIException.class,()->receiver().receiveEncounter(json));
        receiver().receiveEncounter(creation(source,"posta_invalid"));
        ObjectNode wrong=(ObjectNode)mapper.readTree(json);
        ((ObjectNode)wrong.get("payload")).put("patientUuid",UUID.randomUUID().toString());
        assertThrows(APIException.class,()->receiver().receiveEncounter(wrong.toString()));
        assertEquals(1,receiver().getConfirmedEncounterSequence("posta_invalid"));
        assertNull(Context.getObsService().getObsByUuid(value.getUuid()));
        assertEquals(2,receiver().receiveEncounter(json));
        ObjectNode altered=(ObjectNode)mapper.readTree(json);
        ((ObjectNode)altered.path("payload").path("obs").get(0)).put("valueNumeric",999.0);
        assertThrows(APIException.class,()->receiver().receiveEncounter(altered.toString()));
        assertEquals(62.0,Context.getObsService().getObsByUuid(value.getUuid()).getValueNumeric());
    }
	
	@Test public void rejectsExistingObservationUuidInsteadOfOverwritingIt() throws Exception {
        Encounter source=sample();Obs initial=obs(source,5089,61.0);source.addObs(initial);
        receiver().receiveEncounter(creation(source,"posta_conflict"));initial.setValueNumeric(99.0);
        assertThrows(APIException.class,()->receiver().receiveEncounter(addition(source,"posta_conflict",2,initial)));
        assertEquals(61.0,Context.getObsService().getObsByUuid(initial.getUuid()).getValueNumeric());
        assertEquals(1,receiver().getConfirmedEncounterSequence("posta_conflict"));
    }
	
	@Test public void failedDependencyDoesNotSaveEarlierValidObservationOrAdvanceReceipt() throws Exception {
        Encounter source=sample();receiver().receiveEncounter(creation(source,"posta_missing"));
        Obs valid=obs(source,5089,61.0),invalid=obs(source,5089,62.0);
        ObjectNode json=(ObjectNode)mapper.readTree(addition(source,"posta_missing",2,valid,invalid));
        ((ObjectNode)json.path("payload").path("obs").get(1)).put("conceptUuid",UUID.randomUUID().toString());
        assertThrows(APIException.class,()->receiver().receiveEncounter(json.toString()));
        assertNull(Context.getObsService().getObsByUuid(valid.getUuid()));
        assertEquals(1,receiver().getConfirmedEncounterSequence("posta_missing"));
    }
	
	@Test
	public void localRollbackRemovesObservationAndAdditionTogether() throws Exception {
		Encounter e = Context.getEncounterService().saveEncounter(sample());
		Obs result = obs(e, 5089, 62.0);
		Context.getObsService().saveObs(result, null);
		String uuid = result.getUuid();
		assertEquals(beforeAddition + 1, count("select count(*) from synchronizationmr_encounter_addition"));
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertNull(Context.getObsService().getObsByUuid(uuid));
		assertEquals(beforeAddition + 0, count("select count(*) from synchronizationmr_encounter_addition"));
	}
	
	@Test
	public void observationSaveWithoutOuterTransactionCapturesAtomically() throws Exception {
		Encounter e = Context.getEncounterService().saveEncounter(sample());
		Obs prepared = obs(e, 5089, 63.0);
		prepared.getConcept().getDatatype().getName();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		try {
			Obs result = Context.getObsService().saveObs(prepared, null);
			assertNotNull(Context.getObsService().getObsByUuid(result.getUuid()));
			TestTransaction.start();
			assertEquals(beforeAddition + 1, count("select count(*) from synchronizationmr_encounter_addition"));
		}
		finally {
			if (!TestTransaction.isActive())
				TestTransaction.start();
		}
	}
	
	@Test public void lateOrderLinkFailureRollsBackResultsAndReceipt() throws Exception {
        // Deliberately import a result pointing at an order belonging to a different patient.
        String otherOrder=null;
        try(Statement s=getConnection().createStatement();ResultSet r=s.executeQuery("select uuid from orders where patient_id <> 2")) {
            assertTrue(r.next());otherOrder=r.getString(1);
        }
        Encounter source=sample();receiver().receiveEncounter(creation(source,"posta_rollback"));
        Obs result=obs(source,5089,64.0);
        ObjectNode json=(ObjectNode)mapper.readTree(addition(source,"posta_rollback",2,result));
        ((ObjectNode)json.path("payload").path("obs").get(0)).put("orderUuid",otherOrder);
        TestTransaction.flagForCommit();TestTransaction.end();
        try {
            assertThrows(APIException.class,()->receiver().receiveEncounter(json.toString()));
            assertNull(Context.getObsService().getObsByUuid(result.getUuid()));
            assertEquals(1,receiver().getConfirmedEncounterSequence("posta_rollback"));
            TestTransaction.start();
            assertEquals(beforeAddition + 0, count("select count(*) from synchronizationmr_encounter_addition"));
            assertEquals(0,count("select count(*) from synchronizationmr_order_link"));
        } finally {if(!TestTransaction.isActive())TestTransaction.start();}
    }
}

package org.openmrs.module.synchronizationmr.api;

import java.util.*;
import java.sql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.*;
import org.openmrs.Order;
import org.openmrs.api.APIException;
import org.openmrs.api.OrderService;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.advice.OrderCreationAdvice;
import org.openmrs.module.synchronizationmr.sync.*;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class OrderReceiveIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private OrderCreationAdvice advice;
	
	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		advice = new OrderCreationAdvice();
		Context.addAdvice(OrderService.class, advice);
	}
	
	@AfterEach
	public void cleanup() {
		Context.removeAdvice(OrderService.class, advice);
	}
	
	private OrderReceiveService receiver() {
		return Context.getService(OrderReceiveService.class);
	}
	
	private Order sample() {
		Order order = new Order();
		order.setPatient(Context.getPatientService().getPatient(2));
		order.setEncounter(Context.getEncounterService().getEncounter(6));
		order.setConcept(Context.getConceptService().getConcept(5089));
		order.setOrderType(Context.getOrderService().getOrderType(17));
		order.setOrderer(Context.getProviderService().getProvider(1));
		order.setCareSetting(Context.getOrderService().getCareSetting(1));
		order.setDateActivated(new java.util.Date(System.currentTimeMillis() - 10000));
		order.setInstructions("Fictitious test order");
		return order;
	}
	
	private String event(Order order, String origin, long seq) {
		return new OrderCreationPayloadSerializer().serialize(order, origin, seq, UUID.randomUUID().toString(),
		    new java.util.Date());
	}
	
	private String origin() {
		return "posta_" + UUID.randomUUID();
	}
	
	private long number(String sql)throws Exception{try(Statement s=getConnection().createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
	
	@Test
	public void receivesOrderWithPatientEncounterAndOriginalEventWithoutRecapture() throws Exception {
		Order source = sample();
		String origin = origin(), json = event(source, origin, 1);
		long before = number("select order_sequence from synchronizationmr_local_node");
		assertEquals(1, receiver().receiveOrder(json));
		Context.flushSession();
		Context.clearSession();
		Order saved = Context.getOrderService().getOrderByUuid(source.getUuid());
		assertEquals(source.getPatient().getUuid(), saved.getPatient().getUuid());
		assertEquals(source.getEncounter().getUuid(), saved.getEncounter().getUuid());
		assertEquals(source.getInstructions(), saved.getInstructions());
		assertNotNull(saved.getOrderNumber());
		assertEquals(before, number("select order_sequence from synchronizationmr_local_node"));
		assertEquals(1, receiver().receiveOrder(json));
		assertEquals(json, Context.getService(OrderSyncService.class).getOrderEventsAfter(origin, 0, 1).get(0)
		        .getPayloadJson());
	}
	
	@Test public void missingEncounterDoesNotCreatePatientOrConfirm()throws Exception {
        Order source=sample();String origin=origin();ObjectNode json=(ObjectNode)mapper.readTree(event(source,origin,1));
        ((ObjectNode)json.get("payload")).put("encounterUuid",UUID.randomUUID().toString());
        assertThrows(APIException.class,()->receiver().receiveOrder(json.toString()));
        assertNull(Context.getOrderService().getOrderByUuid(source.getUuid()));assertEquals(0,receiver().getConfirmedOrderSequence(origin));
    }
	
	@Test public void rejectsMismatchedPatientAndUnknownCatalog()throws Exception {
        Order source=sample();String origin=origin();ObjectNode json=(ObjectNode)mapper.readTree(event(source,origin,1));
        ((ObjectNode)json.get("payload")).put("patientUuid",Context.getPatientService().getPatient(6).getUuid());
        assertThrows(APIException.class,()->receiver().receiveOrder(json.toString()));
        ((ObjectNode)json.get("payload")).put("patientUuid",source.getPatient().getUuid()).put("conceptUuid",UUID.randomUUID().toString());
        assertThrows(APIException.class,()->receiver().receiveOrder(json.toString()));assertEquals(0,receiver().getConfirmedOrderSequence(origin));
    }
	
	@Test public void rejectsGapChangedRetryAndExistingUuid()throws Exception {
        String origin=origin();Order source=sample();String json=event(source,origin,1);
        assertThrows(APIException.class,()->receiver().receiveOrder(event(sample(),origin,2)));
        assertEquals(1,receiver().receiveOrder(json));ObjectNode changed=(ObjectNode)mapper.readTree(json);
        ((ObjectNode)changed.get("payload")).put("instructions","Changed");
        assertThrows(APIException.class,()->receiver().receiveOrder(changed.toString()));
        assertThrows(APIException.class,()->receiver().receiveOrder(event(source,origin(),1)));
        assertEquals(1,receiver().getConfirmedOrderSequence(origin));
    }
	
	@Test
	public void persistsDeferredObservationLinkAndCompletesItWhenOrderArrives() throws Exception {
		Encounter encounter = new Encounter();
		encounter.setPatient(sample().getPatient());
		encounter.setEncounterDatetime(new java.util.Date(System.currentTimeMillis() - 60000));
		encounter.setEncounterType(Context.getEncounterService().getEncounterType(1));
		Order order = sample();
		order.setEncounter(encounter);
		encounter.addOrder(order);
		Obs obs = new Obs();
		obs.setPerson(encounter.getPatient());
		obs.setEncounter(encounter);
		obs.setObsDatetime(encounter.getEncounterDatetime());
		obs.setConcept(Context.getConceptService().getConcept(5089));
		obs.setValueNumeric(62.0);
		obs.setOrder(order);
		encounter.addObs(obs);
		String origin = origin();
		String encounterJson = new EncounterCreationPayloadSerializer().serialize(encounter, origin, 1, UUID.randomUUID()
		        .toString(), new java.util.Date());
		long before = number("select count(*) from synchronizationmr_order_link");
		Context.getService(EncounterReceiveService.class).receiveEncounter(encounterJson);
		Context.flushSession();
		Context.clearSession();
		assertNull(Context.getObsService().getObsByUuid(obs.getUuid()).getOrder());
		assertEquals(before + 2, number("select count(*) from synchronizationmr_order_link"));
		receiver().receiveOrder(event(order, origin, 1));
		Context.flushSession();
		Context.clearSession();
		assertEquals(order.getUuid(), Context.getObsService().getObsByUuid(obs.getUuid()).getOrder().getUuid());
		assertEquals(before, number("select count(*) from synchronizationmr_order_link"));
		assertEquals(1, Context.getService(EncounterReceiveService.class).receiveEncounter(encounterJson));
	}
	
	@Test
	public void capturesNativeCreationOnlyOnce() throws Exception {
		Order source = sample();
		long before = number("select order_sequence from synchronizationmr_local_node");
		Order saved = Context.getOrderService().saveOrder(source, null);
		assertEquals(before + 1, number("select order_sequence from synchronizationmr_local_node"));
		Context.getService(OrderSyncService.class).recordCreatedOrder(saved);
		assertEquals(before + 1, number("select order_sequence from synchronizationmr_local_node"));
	}
	
	@Test public void databaseFailureRollsBackOrderAndReceipt()throws Exception {
        TestTransaction.flagForCommit();TestTransaction.end();
        try {
            String origin=origin();ObjectNode first=(ObjectNode)mapper.readTree(event(sample(),origin,1));receiver().receiveOrder(first.toString());
            Order second=sample();ObjectNode json=(ObjectNode)mapper.readTree(event(second,origin,2));json.put("eventUuid",first.path("eventUuid").asText());
            assertThrows(RuntimeException.class,()->receiver().receiveOrder(json.toString()));
            assertNull(Context.getOrderService().getOrderByUuid(second.getUuid()));assertEquals(1,receiver().getConfirmedOrderSequence(origin));
            assertEquals(2,receiver().receiveOrder(event(second,origin,2)));
        }finally{TestTransaction.start();}
    }
	
	@Test
	public void receivesDrugAndTestSpecificFields() throws Exception {
		Encounter encounter = new Encounter();
		encounter.setPatient(Context.getPatientService().getPatient(6));
		encounter.setEncounterType(Context.getEncounterService().getEncounterType(1));
		encounter.setEncounterDatetime(new java.util.Date(System.currentTimeMillis() - 60000));
		encounter = Context.getEncounterService().saveEncounter(encounter);
		DrugOrder drug = new DrugOrder();
		org.springframework.beans.BeanUtils.copyProperties(sample(), drug);
		drug.setPatient(encounter.getPatient());
		drug.setEncounter(encounter);
		drug.setOrderType(Context.getOrderService().getOrderType(1));
		drug.setDrug(Context.getConceptService().getDrug(3));
		drug.setConcept(drug.getDrug().getConcept());
		drug.setDose(325.0);
		drug.setDoseUnits(Context.getConceptService().getConcept(50));
		drug.setFrequency(Context.getOrderService().getOrderFrequency(1));
		drug.setRoute(Context.getConceptService().getConcept(22));
		drug.setQuantity(10.0);
		drug.setQuantityUnits(Context.getConceptService().getConcept(51));
		drug.setNumRefills(0);
		drug.setBrandName("Fictitious brand");
		drug.setDosingType(SimpleDosingInstructions.class);
		String origin = origin();
		receiver().receiveOrder(event(drug, origin, 1));
		Context.flushSession();
		Context.clearSession();
		DrugOrder saved = (DrugOrder) Context.getOrderService().getOrderByUuid(drug.getUuid());
		assertEquals(325.0, saved.getDose());
		assertEquals(drug.getDrug().getUuid(), saved.getDrug().getUuid());
		assertEquals(drug.getDoseUnits().getUuid(), saved.getDoseUnits().getUuid());
		assertEquals(drug.getFrequency().getUuid(), saved.getFrequency().getUuid());
		assertEquals("Fictitious brand", saved.getBrandName());
		TestOrder test = new TestOrder();
		org.springframework.beans.BeanUtils.copyProperties(sample(), test);
		test.setOrderType(Context.getOrderService().getOrderType(2));
		test.setConcept(Context.getConceptService().getConcept(5497));
		test.setClinicalHistory("Fictitious history");
		test.setNumberOfRepeats(2);
		test.setLaterality(TestOrder.Laterality.LEFT);
		test.setFrequency(Context.getOrderService().getOrderFrequency(1));
		receiver().receiveOrder(event(test, origin, 2));
		Context.flushSession();
		Context.clearSession();
		TestOrder savedTest = (TestOrder) Context.getOrderService().getOrderByUuid(test.getUuid());
		assertEquals(test.getClinicalHistory(), savedTest.getClinicalHistory());
		assertEquals(2, savedTest.getNumberOfRepeats());
		assertEquals(TestOrder.Laterality.LEFT, savedTest.getLaterality());
	}
	
	@Test
	public void importsStoppedSnapshotThenItsDiscontinuationWithoutReopeningIt() throws Exception {
		Order first = sample();
		String origin = origin();
		java.util.Date stopped = new java.util.Date((System.currentTimeMillis() / 1000 - 5) * 1000);
		ObjectNode initial = (ObjectNode) mapper.readTree(event(first, origin, 1));
		((ObjectNode) initial.get("payload")).put("dateStopped", stopped.toInstant().toString());
		receiver().receiveOrder(initial.toString());
		Context.clearSession();
		Order prior = Context.getOrderService().getOrderByUuid(first.getUuid());
		assertEquals(stopped, prior.getDateStopped());
		Order discontinuation = prior.cloneForDiscontinuing();
		discontinuation.setEncounter(prior.getEncounter());
		discontinuation.setOrderer(prior.getOrderer());
		discontinuation.setDateActivated(stopped);
		discontinuation.setAutoExpireDate(stopped);
		String json = event(discontinuation, origin, 2);
		receiver().receiveOrder(json);
		Context.flushSession();
		Context.clearSession();
		Order saved = Context.getOrderService().getOrderByUuid(discontinuation.getUuid());
		assertEquals(Order.Action.DISCONTINUE, saved.getAction());
		assertEquals(first.getUuid(), saved.getPreviousOrder().getUuid());
		assertEquals(stopped, saved.getPreviousOrder().getDateStopped());
		assertEquals(stopped, saved.getAutoExpireDate());
		assertEquals(2, receiver().receiveOrder(json));
	}
	
	@Test
	public void capturesDiscontinuationCreatedByNativeConvenienceMethod() throws Exception {
		Order saved = Context.getOrderService().saveOrder(sample(), null);
		long before = number("select order_sequence from synchronizationmr_local_node");
		Order dc = Context.getOrderService().discontinueOrder(saved, "Fictitious reason",
		    new java.util.Date(System.currentTimeMillis() - 1000), saved.getOrderer(), saved.getEncounter());
		assertEquals(before + 1, number("select order_sequence from synchronizationmr_local_node"));
		String payload = Context.getService(OrderSyncService.class).getOrderEventsAfter("testServer_1", before, 1).get(0)
		        .getPayloadJson();
		assertEquals(dc.getUuid(), mapper.readTree(payload).path("payload").path("orderUuid").asText());
		assertEquals(saved.getDateStopped().toInstant().toString(),
		    mapper.readTree(payload).path("payload").path("previousOrderDateStopped").asText());
	}
	
	@Test
	public void resolvesObservationImmediatelyWhenOrderAlreadyArrived() throws Exception {
		Order order = sample();
		receiver().receiveOrder(event(order, origin(), 1));
		Encounter encounter = new Encounter();
		encounter.setPatient(order.getPatient());
		encounter.setEncounterDatetime(new java.util.Date());
		encounter.setEncounterType(Context.getEncounterService().getEncounterType(1));
		Obs obs = new Obs();
		obs.setPerson(encounter.getPatient());
		obs.setEncounter(encounter);
		obs.setObsDatetime(encounter.getEncounterDatetime());
		obs.setConcept(Context.getConceptService().getConcept(5089));
		obs.setValueNumeric(60.0);
		obs.setOrder(order);
		encounter.addObs(obs);
		long before = receiver().countPendingOrderLinks();
		String json = new EncounterCreationPayloadSerializer().serialize(encounter, origin(), 1, UUID.randomUUID()
		        .toString(), new java.util.Date());
		Context.getService(EncounterReceiveService.class).receiveEncounter(json);
		Context.flushSession();
		Context.clearSession();
		assertEquals(order.getUuid(), Context.getObsService().getObsByUuid(obs.getUuid()).getOrder().getUuid());
		assertEquals(before, receiver().countPendingOrderLinks());
	}
	
	@Test
	public void importsRevisionAndClosesPreviousOrderAtomically() throws Exception {
		Order first = sample();
		String origin = origin();
		receiver().receiveOrder(event(first, origin, 1));
		Order prior = Context.getOrderService().getOrderByUuid(first.getUuid());
		Order revision = prior.cloneForRevision();
		revision.setEncounter(prior.getEncounter());
		revision.setOrderer(prior.getOrderer());
		revision.setDateActivated(new java.util.Date((System.currentTimeMillis() / 1000 - 2) * 1000));
		revision.setInstructions("Revised instruction");
		ObjectNode json = (ObjectNode) mapper.readTree(event(revision, origin, 2));
		java.util.Date stopped = new java.util.Date(revision.getDateActivated().getTime() - 1000);
		((ObjectNode) json.get("payload")).put("previousOrderDateStopped", stopped.toInstant().toString());
		receiver().receiveOrder(json.toString());
		Context.flushSession();
		Context.clearSession();
		Order saved = Context.getOrderService().getOrderByUuid(revision.getUuid());
		assertEquals(Order.Action.REVISE, saved.getAction());
		assertEquals(stopped, saved.getPreviousOrder().getDateStopped());
		assertEquals("Revised instruction", saved.getInstructions());
	}
	
	@Test public void rejectsOrderForAnotherPatientWithoutLosingPendingLinks() throws Exception {
        Order order=sample();Encounter encounter=new Encounter();encounter.setPatient(Context.getPatientService().getPatient(6));
        encounter.setEncounterDatetime(new java.util.Date());encounter.setEncounterType(Context.getEncounterService().getEncounterType(1));
        encounter.getOrders().add(order);
        String origin=origin();String json=new EncounterCreationPayloadSerializer().serialize(encounter,origin,1,UUID.randomUUID().toString(),new java.util.Date());
        Context.getService(EncounterReceiveService.class).receiveEncounter(json);
        TestTransaction.flagForCommit();TestTransaction.end();
        try {
            long pending=receiver().countPendingOrderLinks();
            assertThrows(APIException.class,()->receiver().receiveOrder(event(order,origin,1)));
            assertNull(Context.getOrderService().getOrderByUuid(order.getUuid()));
            assertEquals(0,receiver().getConfirmedOrderSequence(origin));assertEquals(pending,receiver().countPendingOrderLinks());
        } finally { TestTransaction.start(); }
    }
}

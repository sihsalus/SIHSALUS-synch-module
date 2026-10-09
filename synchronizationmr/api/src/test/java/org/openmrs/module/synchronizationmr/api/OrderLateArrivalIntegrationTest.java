package org.openmrs.module.synchronizationmr.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.time.Instant;
import java.util.*;
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

/** Ejercita API nativa, eventos, conflictos, reintentos y confirmaciones con base real de pruebas. */
public class OrderLateArrivalIntegrationTest extends BaseModuleContextSensitiveTest {

	private final ObjectMapper json = new ObjectMapper();

	private OrderCreationAdvice advice;

	private final String sourceOrigin = "posta_a_" + UUID.randomUUID();

	private final Instant base = Instant.ofEpochSecond(System.currentTimeMillis() / 1000).minusSeconds(100);

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

	private OrderSyncService sync() {
		return Context.getService(OrderSyncService.class);
	}

	private OrderReceiveService receive() {
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
		order.setDateActivated(java.util.Date.from(base.minusSeconds(10)));
		order.setInstructions("Prescripción ficticia que debe conservarse");
		return order;
	}

	private Order imported() {
		Order order = sample();
		String event = new OrderCreationPayloadSerializer().serialize(order, sourceOrigin, 1, UUID.randomUUID().toString(),
		    java.util.Date.from(base));
		receive().receiveOrder(event);
		return Context.getOrderService().getOrderByUuid(order.getUuid());
	}

	private Order fresh(Order order) {
		Context.flushSession();
		Context.clearSession();
		return Context.getOrderService().getOrderByUuid(order.getUuid());
	}

	private ObjectNode branch(Order previous, String origin, long seq, long seconds, boolean discontinue) throws Exception {
		Order next = discontinue ? previous.cloneForDiscontinuing() : previous.cloneForRevision();
		next.setEncounter(previous.getEncounter());
		next.setOrderer(previous.getOrderer());
		next.setDateActivated(java.util.Date.from(base.plusSeconds(seconds)));
		if (discontinue)
			next.setAutoExpireDate(next.getDateActivated());
		next.setInstructions("Cambio de " + origin);
		ObjectNode event = (ObjectNode) json.readTree(new OrderCreationPayloadSerializer().serialize(next, origin, seq, UUID
		        .randomUUID().toString(), java.util.Date.from(base.plusSeconds(seconds))));
		((ObjectNode) event.get("payload")).put("previousOrderDateStopped", base.plusSeconds(seconds - 1).toString());
		return event;
	}

	private Order saved(ObjectNode event) {
		Context.flushSession();
		Context.clearSession();
		return Context.getOrderService().getOrderByUuid(event.path("payload").path("orderUuid").asText());
	}

	private Encounter encounter(boolean withVisit) {
		Encounter e = new Encounter();
		e.setPatient(Context.getPatientService().getPatient(2));
		e.setEncounterType(Context.getEncounterService().getEncounterType(1));
		e.setLocation(Context.getLocationService().getLocation(1));
		e.setEncounterDatetime(java.util.Date.from(base.minusSeconds(20)));
		if (withVisit) {
			Visit v = new Visit();
			v.setPatient(e.getPatient());
			v.setVisitType(Context.getVisitService().getVisitType(1));
			v.setStartDatetime(java.util.Date.from(base.minusSeconds(30)));
			e.setVisit(v);
		}
		Context.getService(EncounterReceiveService.class).receiveEncounter(
		    new EncounterCreationPayloadSerializer().serialize(e, sourceOrigin, 1, UUID.randomUUID().toString(),
		        java.util.Date.from(base)));
		return Context.getEncounterService().getEncounterByUuid(e.getUuid());
	}

	private String orderEvent(Order order, long seq) {
		return new OrderCreationPayloadSerializer().serialize(order, sourceOrigin, seq, UUID.randomUUID().toString(),
		    java.util.Date.from(base));
	}

	private void annul(Encounter e, boolean visit) {
		String event;
		if (visit) {
			Visit v = new Visit();
			v.setUuid(e.getVisit().getUuid());
			v.setPatient(e.getPatient());
			v.setDateVoided(java.util.Date.from(base.plusSeconds(50)));
			v.setVoidReason("Visita anulada en prueba");
			event = VisitVoidEvent.create(v, e.getUuid(), sourceOrigin, 2, base.plusSeconds(50));
		} else {
			Encounter copy = new Encounter();
			copy.setUuid(e.getUuid());
			copy.setPatient(e.getPatient());
			copy.setDateVoided(java.util.Date.from(base.plusSeconds(50)));
			copy.setVoidReason("Encuentro anulado en prueba");
			event = EncounterVoidEvent.create(copy, sourceOrigin, 2, base.plusSeconds(50));
		}
		Context.getService(EncounterReceiveService.class).receiveEncounter(event);
	}

	private void late(boolean visit) throws Exception {
		long localBefore = sync().getHighestOrderSequence("testServer_1");
		Encounter e = encounter(visit);
		Order order = sample();
		order.setEncounter(e);
		String event = orderEvent(order, 1);
		annul(e, visit);
		assertEquals(1, receive().receiveOrder(event));
		Order saved = fresh(order);
		assertTrue(saved.getVoided());
		assertTrue(saved.getEncounter().getVoided());
		assertEquals(java.util.Date.from(base.plusSeconds(50)), saved.getDateVoided());
		assertEquals(order.getInstructions(), saved.getInstructions());
		if (visit)
			assertTrue(saved.getEncounter().getVisit().getVoided());
		assertEquals(1, receive().receiveOrder(event));
		assertEquals(event, sync().getOrderEventsAfter(sourceOrigin, 0, 1).get(0).getPayloadJson());
		assertEquals(localBefore, sync().getHighestOrderSequence("testServer_1"));
	}

	@Test
	public void newOrderAfterEncounterAnnulmentIsHistoryAndReplayIsExact() throws Exception {
		late(false);
	}

	@Test
	public void newOrderAfterVisitAnnulmentIsHistoryAndReplayIsExact() throws Exception {
		late(true);
	}

	@Test
	public void revisionsAndDiscontinuationAfterAnnulmentDoNotReactivateParent() throws Exception {
		Encounter e = encounter(false);
		Order order = sample();
		order.setEncounter(e);
		receive().receiveOrder(orderEvent(order, 1));
		Order root = fresh(order);
		ObjectNode revise = branch(root, sourceOrigin, 2, 10, false);
		ObjectNode stop = branch(root, sourceOrigin, 3, 20, true);
		annul(e, false);
		receive().receiveOrder(revise.toString());
		receive().receiveOrder(stop.toString());
		assertTrue(fresh(root).getVoided());
		assertNull(fresh(root).getDateStopped());
		assertTrue(saved(revise).getVoided());
		assertTrue(saved(stop).getVoided());
		assertEquals(3, receive().getConfirmedOrderSequence(sourceOrigin));
	}

	@Test public void unpublishedAnnulmentRemainsRejected() throws Exception {
        Encounter e = encounter(false);
        Order order = sample(); order.setEncounter(e);
        String event = orderEvent(order, 1);
        Context.getEncounterService().voidEncounter(e, "Anulación local sin evento");
        assertThrows(APIException.class, () -> receive().receiveOrder(event));
        assertEquals(0, receive().getConfirmedOrderSequence(sourceOrigin));
    }

	@Test
	public void nextValidOrderIsNotBlockedByHistoricalOne() throws Exception {
		Encounter e = encounter(false);
		Order order = sample();
		order.setEncounter(e);
		String first = orderEvent(order, 1);
		annul(e, false);
		receive().receiveOrder(first);
		Order next = sample();
		receive().receiveOrder(orderEvent(next, 2));
		assertTrue(fresh(order).getVoided());
		assertFalse(fresh(next).getVoided());
		assertEquals(2, receive().getConfirmedOrderSequence(sourceOrigin));
	}

	private void observationLink(boolean beforeOrder) throws Exception {
        Encounter e = encounter(false);
        Order order = sample(); order.setEncounter(e); String event = orderEvent(order, 1);
        Obs obs = new Obs(e.getPatient(), order.getConcept(), java.util.Date.from(base), e.getLocation());
        obs.setEncounter(e); obs.setOrder(order); obs.setValueNumeric(14.0);
        String addition = new EncounterCreationPayloadSerializer().serializeAddition(e, Arrays.asList(obs),
            new HashSet<>(Arrays.asList(obs.getUuid())), sourceOrigin, 3, UUID.randomUUID().toString(), java.util.Date.from(base));
        annul(e, false);
        if (beforeOrder) Context.getService(EncounterReceiveService.class).receiveEncounter(addition);
        receive().receiveOrder(event);
        if (!beforeOrder) Context.getService(EncounterReceiveService.class).receiveEncounter(addition);
        fresh(order);
        Obs stored = Context.getObsService().getObsByUuid(obs.getUuid());
        assertTrue(stored.getVoided()); assertEquals(14.0, stored.getValueNumeric());
        assertEquals(order.getUuid(), stored.getOrder().getUuid());
        assertTrue(stored.getOrder().getVoided());
        assertEquals(0, receive().countPendingOrderLinks());
    }

	@Test
	public void resultBeforeLateOrderKeepsHistoricalLink() throws Exception {
		observationLink(true);
	}

	@Test
	public void resultAfterLateOrderKeepsHistoricalLink() throws Exception {
		observationLink(false);
	}

	@Test
	public void rollbackDoesNotConfirmOrLeaveHistoricalOrder() throws Exception {
		Encounter e = encounter(false);
		Order order = sample();
		order.setEncounter(e);
		String event = orderEvent(order, 1);
		annul(e, false);
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		try {
			receive().receiveOrder(event);
			assertTrue(fresh(order).getVoided());
			TestTransaction.flagForRollback();
			TestTransaction.end();
			TestTransaction.start();
			assertNull(Context.getOrderService().getOrderByUuid(order.getUuid()));
			assertEquals(0, receive().getConfirmedOrderSequence(sourceOrigin));
			assertTrue(Context.getEncounterService().getEncounterByUuid(e.getUuid()).getVoided());
			assertEquals(1, receive().receiveOrder(event));
		}
		finally {
			if (!TestTransaction.isActive())
				TestTransaction.start();
		}
	}

	@Test public void invalidPatientStillRejectedWithoutConfirmation() throws Exception {
        Encounter e = encounter(false); Order order = sample(); order.setEncounter(e);
        ObjectNode event = (ObjectNode) json.readTree(orderEvent(order, 1));
        ((ObjectNode)event.get("payload")).put("patientUuid", Context.getPatientService().getPatient(6).getUuid());
        annul(e, false);
        assertThrows(APIException.class, () -> receive().receiveOrder(event.toString()));
        assertEquals(0, receive().getConfirmedOrderSequence(sourceOrigin));
    }

	@Test public void nativeValidationStillRejectsInvalidDates() throws Exception {
        Encounter e = encounter(false); Order order = sample(); order.setEncounter(e);
        ObjectNode event = (ObjectNode) json.readTree(orderEvent(order, 1));
        ((ObjectNode)event.get("payload")).put("dateActivated", base.minusSeconds(1000).toString());
        annul(e, false);
        assertThrows(APIException.class, () -> receive().receiveOrder(event.toString()));
        assertEquals(0, receive().getConfirmedOrderSequence(sourceOrigin));
        assertNull(Context.getOrderService().getOrderByUuid(order.getUuid()));
    }

	@Test public void missingPredecessorStillBlocksHistoricalRevision() throws Exception {
        Encounter e = encounter(false); Order order = sample(); order.setEncounter(e);
        ObjectNode event = branch(order, sourceOrigin, 1, 10, false);
        annul(e, false);
        assertThrows(APIException.class, () -> receive().receiveOrder(event.toString()));
        assertEquals(0, receive().getConfirmedOrderSequence(sourceOrigin));
    }

	@Test
	public void drugOrderIsStoredAsVoidedWithDoseAndDrugPreserved() throws Exception {
		Encounter e = encounter(true);
		DrugOrder drug = new DrugOrder();
		org.springframework.beans.BeanUtils.copyProperties(sample(), drug);
		drug.setEncounter(e);
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
		drug.setDosingType(SimpleDosingInstructions.class);
		String event = orderEvent(drug, 1);
		annul(e, true);
		receive().receiveOrder(event);
		DrugOrder stored = (DrugOrder) fresh(drug);
		assertTrue(stored.getVoided());
		assertEquals(325.0, stored.getDose());
		assertEquals(drug.getDrug().getUuid(), stored.getDrug().getUuid());
		assertEquals(10.0, stored.getQuantity());
	}

	@Test
	public void lateRenewalDoesNotReactivatePreviousOrder() throws Exception {
		Encounter e = encounter(false);
		Order order = sample();
		order.setEncounter(e);
		receive().receiveOrder(orderEvent(order, 1));
		Order root = fresh(order);
		Order renewal = root.cloneForRevision();
		renewal.setAction(Order.Action.RENEW);
		renewal.setEncounter(e);
		renewal.setOrderer(root.getOrderer());
		renewal.setDateActivated(java.util.Date.from(base.plusSeconds(10)));
		String event = orderEvent(renewal, 2);
		annul(e, false);
		receive().receiveOrder(event);
		assertTrue(fresh(root).getVoided());
		assertTrue(fresh(renewal).getVoided());
		assertEquals(root.getUuid(), fresh(renewal).getPreviousOrder().getUuid());
	}

	@Test
	public void lateEncounterThenItsOrderStayVoidedUnderDeletedVisit() throws Exception {
		Encounter anchor = encounter(true);
		Encounter late = new Encounter();
		late.setPatient(anchor.getPatient());
		late.setEncounterType(anchor.getEncounterType());
		late.setLocation(anchor.getLocation());
		late.setEncounterDatetime(java.util.Date.from(base));
		late.setVisit(anchor.getVisit());
		String encounterEvent = new EncounterCreationPayloadSerializer().serialize(late, sourceOrigin, 3, UUID.randomUUID()
		        .toString(), java.util.Date.from(base));
		Order order = sample();
		order.setEncounter(late);
		order.setDateActivated(java.util.Date.from(base.plusSeconds(1)));
		String event = orderEvent(order, 1);
		annul(anchor, true);
		Context.getService(EncounterReceiveService.class).receiveEncounter(encounterEvent);
		receive().receiveOrder(event);
		Order stored = fresh(order);
		assertTrue(stored.getVoided());
		assertTrue(stored.getEncounter().getVoided());
		assertTrue(stored.getEncounter().getVisit().getVoided());
		assertEquals(1, receive().getConfirmedOrderSequence(sourceOrigin));
	}

	@Test
	public void historicalRevisionDoesNotChangeActivePredecessorInAnotherEncounter() throws Exception {
		Encounter e = encounter(false);
		Order root = imported();
		ObjectNode revision = branch(root, sourceOrigin, 2, 10, false);
		((ObjectNode) revision.get("payload")).put("encounterUuid", e.getUuid());
		annul(e, false);
		receive().receiveOrder(revision.toString());
		assertFalse(fresh(root).getVoided());
		assertNull(fresh(root).getDateStopped());
		assertTrue(saved(revision).getVoided());
	}

	@Test public void technicalRoleNeedsDeleteOrdersForHistoricalImport() throws Exception {
        Encounter e = encounter(false); Order order = sample(); order.setEncounter(e);
        String event = orderEvent(order, 1); annul(e, false);
        String username = "late" + UUID.randomUUID().toString().substring(0,8);
        Role role = new Role(username);
        for (String name : Arrays.asList("Receive Synchronization Records", "View Synchronization Records",
            "Add Orders", "Get Orders", "Edit Orders", "Delete Orders", "Get Global Properties",
            "Get Patients", "Get People", "Get Encounters", "Get Concepts", "Get Providers",
            "Get Order Types", "Get Care Settings", "Get Order Frequencies")) {
            if (Context.getUserService().getPrivilege(name)==null)
                Context.getUserService().savePrivilege(new Privilege(name,"Prueba"));
        }
        for (Privilege privilege : Context.getUserService().getAllPrivileges()) {
            if (!"Delete Orders".equals(privilege.getPrivilege())) role.addPrivilege(privilege);
        }
        Context.getUserService().saveRole(role);
        User user = new User(); user.setPerson(Context.getPersonService().getPerson(2));
        user.setUsername(username); user.addRole(role);
        String password = UUID.randomUUID().toString()+"aA1!";
        Context.getUserService().createUser(user,password);
        org.openmrs.api.context.Credentials admin = getCredentials();
        OrderReceiveService service = receive(); Context.logout();
        try {
            Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(username,password));
            assertTrue(assertThrows(org.openmrs.api.APIAuthenticationException.class, () -> service.receiveOrder(event))
                .getMessage().contains("Delete Orders"));
        } finally { Context.logout(); Context.authenticate(admin); }
        assertNull(Context.getOrderService().getOrderByUuid(order.getUuid()));
        assertEquals(0,receive().getConfirmedOrderSequence(sourceOrigin));
        role.addPrivilege(Context.getUserService().getPrivilege("Delete Orders"));
        Context.getUserService().saveRole(role); Context.logout();
        try {
            Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(username,password));
            assertEquals(1, service.receiveOrder(event));
        } finally { Context.logout(); Context.authenticate(admin); }
        assertTrue(fresh(order).getVoided());
    }
}

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
public class OrderFulfillmentIntegrationTest extends BaseModuleContextSensitiveTest {

	private final ObjectMapper json = new ObjectMapper();

	private OrderCreationAdvice advice;

	private final String sourceOrigin = "posta_a_" + UUID.randomUUID();

	private final Instant base = Instant.now().minusSeconds(100);

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

	private ObjectNode change(Order order, String origin, long seq, long seconds, String status) throws Exception {
		ObjectNode event = (ObjectNode) json
		        .readTree(OrderFulfillment.create(order, origin, seq, base.plusSeconds(seconds)));
		((ObjectNode) event.get("payload")).put("fulfillerStatus", status);
		return event;
	}

	private Order fresh(Order order) {
		Context.flushSession();
		Context.clearSession();
		return Context.getOrderService().getOrderByUuid(order.getUuid());
	}

	private long number(String sql) throws Exception {
        try (Statement s = getConnection().createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }

	@Test
	public void capturesNativeThreeAndFourArgumentUpdatesInOneSequenceWithoutNoops() throws Exception {
		Order order = Context.getOrderService().saveOrder(sample(), null);
		long initial = sync().getHighestOrderSequence("testServer_1");
		String original = sync().getOrderEventsAfter("testServer_1", initial - 1, 1).get(0).getPayloadJson();
		Context.getOrderService().updateOrderFulfillerStatus(order, Order.FulfillerStatus.IN_PROGRESS, "Procesando");
		Context.getOrderService()
		        .updateOrderFulfillerStatus(order, Order.FulfillerStatus.COMPLETED, "Finalizado", "LAB-001");
		Context.getOrderService().updateOrderFulfillerStatus(order, null, null, null);
		Context.getOrderService()
		        .updateOrderFulfillerStatus(order, Order.FulfillerStatus.COMPLETED, "Finalizado", "LAB-001");
		assertEquals(initial + 2, sync().getHighestOrderSequence("testServer_1"));
		List<OrderSyncEvent> page = sync().getOrderEventsAfter("testServer_1", initial - 1, 3);
		assertEquals(3, page.size());
		assertEquals(original, page.get(0).getPayloadJson());
		assertEquals("ORDER", page.get(2).getEntityType());
		OrderIncomingEvent event = new OrderIncomingEvent(page.get(2).getPayloadJson());
		assertTrue(event.fulfillmentUpdate);
		assertEquals("COMPLETED", event.root.path("payload").path("fulfillerStatus").asText());
		assertEquals("LAB-001", event.root.path("payload").path("accessionNumber").asText());
		assertEquals(Order.FulfillerStatus.COMPLETED, fresh(order).getFulfillerStatus());
	}

	@Test
	public void receivesAndRelaysWithoutRecaptureOrChangingPrescriptionAndRetriesAreIdempotent() throws Exception {
		Order order = imported();
		ObjectNode event = change(order, sourceOrigin, 2, 1, "COMPLETED");
		((ObjectNode) event.get("payload")).put("fulfillerComment", "Resultado registrado")
		        .put("accessionNumber", "LAB-002");
		long local = number("select order_sequence from synchronizationmr_local_node");
		assertEquals(2, receive().receiveOrder(event.toString()));
		assertEquals(2, receive().receiveOrder(event.toString()));
		assertEquals(local, number("select order_sequence from synchronizationmr_local_node"));
		Order saved = fresh(order);
		assertEquals(Order.FulfillerStatus.COMPLETED, saved.getFulfillerStatus());
		assertEquals("Resultado registrado", saved.getFulfillerComment());
		assertEquals("LAB-002", saved.getAccessionNumber());
		assertEquals(order.getInstructions(), saved.getInstructions());
		assertEquals(order.getDateActivated(), saved.getDateActivated());
		assertNull(saved.getDateStopped());
		assertEquals(event.toString(), sync().getOrderEventsAfter(sourceOrigin, 1, 1).get(0).getPayloadJson());
		assertEquals(1, number("select count(*) from synchronizationmr_order_update"));
	}

	@Test
    public void rejectsChangedReplayAndGap() throws Exception {
        Order order = imported();
        assertThrows(APIException.class, () -> receive().receiveOrder(change(order, sourceOrigin, 3, 2, "COMPLETED").toString()));
        ObjectNode event = change(order, sourceOrigin, 2, 1, "IN_PROGRESS");
        receive().receiveOrder(event.toString());
        ((ObjectNode) event.get("payload")).put("fulfillerStatus", "COMPLETED");
        assertThrows(APIException.class, () -> receive().receiveOrder(event.toString()));
        assertEquals(2, receive().getConfirmedOrderSequence(sourceOrigin));
    }

	@Test
    public void missingOrderDoesNotConfirm() throws Exception {
        String event = change(sample(), "posta_b", 1, 1, "COMPLETED").toString();
        assertThrows(APIException.class, () -> receive().receiveOrder(event));
        assertEquals(0, receive().getConfirmedOrderSequence("posta_b"));
        assertEquals(0, number("select count(*) from synchronizationmr_order_update"));
    }

	@Test
    public void rejectsForeignPatientAndEncounter() throws Exception {
        Order order = imported();
        for (String field : Arrays.asList("patientUuid", "encounterUuid")) {
            ObjectNode event = change(order, sourceOrigin, 2, 1, "COMPLETED");
            ((ObjectNode) event.get("payload")).put(field, UUID.randomUUID().toString());
            assertThrows(APIException.class, () -> receive().receiveOrder(event.toString()));
        }
        assertEquals(1, receive().getConfirmedOrderSequence(sourceOrigin));
        assertNull(fresh(order).getFulfillerStatus());
    }

	@Test
    public void rejectsUnknownFieldsInvalidStatusMissingValuesAndDuplicateJsonKeys() throws Exception {
        ObjectNode valid = change(sample(), sourceOrigin, 1, 1, "COMPLETED");
        for (String field : Arrays.asList("fulfillerStatus", "fulfillerComment", "accessionNumber")) {
            ObjectNode bad = valid.deepCopy(); ((ObjectNode) bad.get("payload")).remove(field);
            assertThrows(APIException.class, () -> new OrderIncomingEvent(bad.toString()));
        }
        ObjectNode bad = valid.deepCopy(); ((ObjectNode) bad.get("payload")).put("fulfillerStatus", "UNKNOWN");
        assertThrows(APIException.class, () -> new OrderIncomingEvent(bad.toString()));
        ObjectNode extra = valid.deepCopy(); ((ObjectNode) extra.get("payload")).put("instructions", "No admitido");
        assertThrows(APIException.class, () -> new OrderIncomingEvent(extra.toString()));
        assertThrows(APIException.class, () -> new OrderIncomingEvent(valid.toString().replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2")));
        assertThrows(APIException.class, () -> new OrderIncomingEvent(valid.toString() + "{}"));
    }

	@Test
	public void latestWinsInBothArrivalOrdersAndLosingEventIsRetained() throws Exception {
		for (boolean reverse : Arrays.asList(false, true)) {
			Order order = importedWithUniqueOrigin();
			String a = "a_" + UUID.randomUUID(), b = "b_" + UUID.randomUUID();
			ObjectNode older = change(order, a, 1, 1, "IN_PROGRESS"), newer = change(order, b, 1, 2, "COMPLETED");
			receive().receiveOrder((reverse ? newer : older).toString());
			receive().receiveOrder((reverse ? older : newer).toString());
			assertEquals(Order.FulfillerStatus.COMPLETED, fresh(order).getFulfillerStatus());
			assertEquals(1, receive().getConfirmedOrderSequence(a));
			assertEquals(1, receive().getConfirmedOrderSequence(b));
			assertEquals(older.toString(), sync().getOrderEventsAfter(a, 0, 1).get(0).getPayloadJson());
		}
	}

	private Order importedWithUniqueOrigin() {
		Order order = sample();
		receive().receiveOrder(
		    new OrderCreationPayloadSerializer().serialize(order, "seed_" + UUID.randomUUID(), 1, UUID.randomUUID()
		            .toString(), java.util.Date.from(base)));
		return Context.getOrderService().getOrderByUuid(order.getUuid());
	}

	@Test
	public void tieUsesOriginRegardlessOfArrivalOrder() throws Exception {
		for (boolean reverse : Arrays.asList(false, true)) {
			Order order = importedWithUniqueOrigin();
			String suffix = UUID.randomUUID().toString();
			ObjectNode a = change(order, "a_" + suffix, 1, 2, "IN_PROGRESS");
			ObjectNode b = change(order, "b_" + suffix, 1, 2, "COMPLETED");
			receive().receiveOrder((reverse ? b : a).toString());
			receive().receiveOrder((reverse ? a : b).toString());
			assertEquals(Order.FulfillerStatus.COMPLETED, fresh(order).getFulfillerStatus());
		}
	}

	@Test
	public void appliesExplicitNullSnapshotWithoutPreservingStaleValues() throws Exception {
		Order order = imported();
		ObjectNode first = change(order, sourceOrigin, 2, 1, "COMPLETED");
		((ObjectNode) first.get("payload")).put("fulfillerComment", "Comentario").put("accessionNumber", "LAB");
		receive().receiveOrder(first.toString());
		ObjectNode second = change(order, sourceOrigin, 3, 2, null);
		((ObjectNode) second.get("payload")).putNull("fulfillerComment").putNull("accessionNumber");
		receive().receiveOrder(second.toString());
		Order saved = fresh(order);
		assertNull(saved.getFulfillerStatus());
		assertNull(saved.getFulfillerComment());
		assertNull(saved.getAccessionNumber());
	}

	@Test
	public void historicalPreparationUsesLatestStateWithoutPublishingAnUpdateBeforeCreation() throws Exception {
		Context.removeAdvice(OrderService.class, advice);
		Order order = Context.getOrderService().saveOrder(sample(), null);
		Context.addAdvice(OrderService.class, advice);
		Context.getOrderService().updateOrderFulfillerStatus(order, Order.FulfillerStatus.COMPLETED, "Anterior al módulo");
		assertEquals(0, number("select count(*) from synchronizationmr_order_update"));
		sync().recordCreatedOrder(order);
		String event = sync().getOrderEventsAfter("testServer_1", sync().getHighestOrderSequence("testServer_1") - 1, 1)
		        .get(0).getPayloadJson();
		assertEquals("COMPLETED", json.readTree(event).path("payload").path("fulfillerStatus").asText());
	}

	@Test
	public void incomingUpdateNeverReactivatesVoidedOrder() throws Exception {
		Order order = imported();
		Context.getOrderService().voidOrder(order, "Prueba ficticia");
		receive().receiveOrder(change(order, sourceOrigin, 2, 1, "COMPLETED").toString());
		Order saved = fresh(order);
		assertTrue(saved.getVoided());
		assertNull(saved.getFulfillerStatus());
		assertEquals(2, receive().getConfirmedOrderSequence(sourceOrigin));
	}

	@Test
	public void localChangeAfterRemoteVersionUsesLaterTimestamp() throws Exception {
		Order order = imported();
		ObjectNode remote = change(order, sourceOrigin, 2, 3600, "IN_PROGRESS");
		receive().receiveOrder(remote.toString());
		Context.getOrderService().updateOrderFulfillerStatus(fresh(order), Order.FulfillerStatus.COMPLETED, null);
		OrderIncomingEvent local = new OrderIncomingEvent(sync()
		        .getOrderEventsAfter("testServer_1", sync().getHighestOrderSequence("testServer_1") - 1, 1).get(0)
		        .getPayloadJson());
		assertTrue(local.occurredAt.after(new OrderIncomingEvent(remote.toString()).occurredAt));
	}

	@Test
	public void rollbackUndoesClinicalChangeEventAndSequence() throws Exception {
		Order order = Context.getOrderService().saveOrder(sample(), null);
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		try {
			long before = sync().getHighestOrderSequence("testServer_1");
			Context.getOrderService().updateOrderFulfillerStatus(Context.getOrderService().getOrderByUuid(order.getUuid()),
			    Order.FulfillerStatus.COMPLETED, "Prueba");
			assertEquals(before + 1, sync().getHighestOrderSequence("testServer_1"));
			TestTransaction.flagForRollback();
			TestTransaction.end();
			TestTransaction.start();
			assertNull(Context.getOrderService().getOrderByUuid(order.getUuid()).getFulfillerStatus());
			assertEquals(before, sync().getHighestOrderSequence("testServer_1"));
			assertEquals(0, number("select count(*) from synchronizationmr_order_update"));
		}
		finally {
			if (!TestTransaction.isActive())
				TestTransaction.start();
		}
	}

	@Test
    public void rejectsEventUuidReusedAcrossCreationAndUpdate() throws Exception {
        Order order = imported();
        ObjectNode event = change(order, sourceOrigin, 2, 1, "COMPLETED");
        String first = sync().getOrderEventsAfter(sourceOrigin, 0, 1).get(0).getEventUuid();
        event.put("eventUuid", first);
        assertThrows(APIException.class, () -> receive().receiveOrder(event.toString()));
        assertEquals(1, receive().getConfirmedOrderSequence(sourceOrigin));
        assertNull(fresh(order).getFulfillerStatus());
    }

	@Test
	public void receiveRollbackUndoesStatusReceiptAndVersion() throws Exception {
		Order order = imported();
		String event = change(order, sourceOrigin, 2, 1, "COMPLETED").toString();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		try {
			receive().receiveOrder(event);
			assertEquals(2, receive().getConfirmedOrderSequence(sourceOrigin));
			TestTransaction.flagForRollback();
			TestTransaction.end();
			TestTransaction.start();
			assertEquals(1, receive().getConfirmedOrderSequence(sourceOrigin));
			assertNull(Context.getOrderService().getOrderByUuid(order.getUuid()).getFulfillerStatus());
			assertEquals(0, number("select count(*) from synchronizationmr_order_state where order_id=" + order.getId()));
			assertEquals(2, receive().receiveOrder(event));
		}
		finally {
			if (!TestTransaction.isActive())
				TestTransaction.start();
		}
	}

	@Test
    public void technicalRoleNeedsEditOrdersAndCanApplyWithoutAdministrator() throws Exception {
        for (boolean edit : Arrays.asList(false, true)) {
            Order order = importedWithUniqueOrigin();
            String event = change(order, "peer_" + UUID.randomUUID(), 1, 1, "COMPLETED").toString();
            String username = "of" + UUID.randomUUID().toString().substring(0, 8);
            Role role = new Role(username);
            List<String> names = new ArrayList<>(Arrays.asList("Receive Synchronization Records", "View Synchronization Records",
                "Add Orders", "Get Orders", "Get Global Properties", "Get Patients", "Get People", "Get Encounters", "Get Concepts"));
            if (edit) names.add("Edit Orders");
            for (String name : names) {
                Privilege privilege = Context.getUserService().getPrivilege(name);
                if (privilege == null) privilege = Context.getUserService().savePrivilege(new Privilege(name, "Prueba"));
                role.addPrivilege(privilege);
            }
            Context.getUserService().saveRole(role);
            User user = new User(); user.setPerson(Context.getPersonService().getPerson(2));
            user.setUsername(username); user.addRole(role);
            String password = UUID.randomUUID().toString() + "aA1!";
            Context.getUserService().createUser(user, password);
            org.openmrs.api.context.Credentials admin = getCredentials();
            OrderReceiveService service = receive();
            Context.logout();
            try {
                Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(username, password));
                if (edit) assertEquals(1, service.receiveOrder(event));
                else assertThrows(org.openmrs.api.APIAuthenticationException.class, () -> service.receiveOrder(event));
            } finally { Context.logout(); Context.authenticate(admin); }
            assertEquals(edit ? Order.FulfillerStatus.COMPLETED : null, fresh(order).getFulfillerStatus());
        }
    }

	@Test
	public void fulfillmentDoesNotAlterResultObservationOrItsOrderLink() throws Exception {
		Order order = imported();
		Obs obs = new Obs(order.getPatient(), order.getConcept(), java.util.Date.from(base), order.getEncounter()
		        .getLocation());
		obs.setEncounter(order.getEncounter());
		obs.setOrder(order);
		obs.setValueNumeric(65.0);
		Context.getObsService().saveObs(obs, null);
		receive().receiveOrder(change(order, sourceOrigin, 2, 1, "COMPLETED").toString());
		Context.flushSession();
		Context.clearSession();
		Obs saved = Context.getObsService().getObsByUuid(obs.getUuid());
		assertEquals(65.0, saved.getValueNumeric());
		assertFalse(saved.getVoided());
		assertEquals(order.getUuid(), saved.getOrder().getUuid());
		assertEquals(Order.FulfillerStatus.COMPLETED, saved.getOrder().getFulfillerStatus());
	}
}

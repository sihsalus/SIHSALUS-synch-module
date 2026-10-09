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
public class OrderConflictIntegrationTest extends BaseModuleContextSensitiveTest {

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

	private void competing(boolean firstDc, boolean secondDc, boolean reverse) throws Exception {
		Order root = imported();
		ObjectNode first = branch(root, "posta_b_" + sourceOrigin, 1, 10, firstDc);
		ObjectNode second = branch(root, "posta_c_" + sourceOrigin, 1, 20, secondDc);
		receive().receiveOrder((reverse ? second : first).toString());
		receive().receiveOrder((reverse ? first : second).toString());
		assertTrue(saved(first).getVoided());
		assertFalse(saved(second).getVoided());
		assertEquals(base.plusSeconds(19).toEpochMilli(), fresh(root).getDateStopped().getTime());
		assertEquals(1, receive().receiveOrder(first.toString()));
		assertEquals(1, receive().receiveOrder(second.toString()));
		assertTrue(saved(first).getVoided());
		assertFalse(saved(second).getVoided());
		assertEquals(1, number("select count(*) from synchronizationmr_order_conflict"));
	}

	@Test
	public void revisionsOldThenNew() throws Exception {
		competing(false, false, false);
	}

	@Test
	public void revisionsNewThenOld() throws Exception {
		competing(false, false, true);
	}

	@Test
	public void cancellationWinsAfterRevision() throws Exception {
		competing(false, true, false);
	}

	@Test
	public void cancellationWinsBeforeRevision() throws Exception {
		competing(false, true, true);
	}

	@Test
	public void revisionWinsAfterCancellation() throws Exception {
		competing(true, false, false);
	}

	@Test
	public void revisionWinsBeforeCancellation() throws Exception {
		competing(true, false, true);
	}

	@Test
	public void cancellationsOldThenNew() throws Exception {
		competing(true, true, false);
	}

	@Test
	public void cancellationsNewThenOld() throws Exception {
		competing(true, true, true);
	}

	@Test
	public void lateDescendantOfLosingBranchRemainsHistorical() throws Exception {
		Order root = imported();
		ObjectNode first = branch(root, "posta_b_" + sourceOrigin, 1, 10, false);
		ObjectNode second = branch(root, "posta_c_" + sourceOrigin, 1, 20, false);
		receive().receiveOrder(first.toString());
		ObjectNode descendant = branch(saved(first), "posta_b_" + sourceOrigin, 2, 30, false);
		receive().receiveOrder(second.toString());
		receive().receiveOrder(descendant.toString());
		assertTrue(saved(first).getVoided());
		assertTrue(saved(descendant).getVoided());
		assertFalse(saved(second).getVoided());
		assertNull(saved(second).getDateStopped());
	}

	@Test
	public void descendantReceivedBeforeConflictIsAlsoSuperseded() throws Exception {
		Order root = imported();
		ObjectNode first = branch(root, "posta_b_" + sourceOrigin, 1, 10, false);
		ObjectNode second = branch(root, "posta_c_" + sourceOrigin, 1, 20, false);
		receive().receiveOrder(first.toString());
		ObjectNode descendant = branch(saved(first), "posta_b_" + sourceOrigin, 2, 30, false);
		receive().receiveOrder(descendant.toString());
		receive().receiveOrder(second.toString());
		assertTrue(saved(first).getVoided());
		assertTrue(saved(descendant).getVoided());
		assertFalse(saved(second).getVoided());
		assertNull(saved(second).getDateStopped());
	}

	@Test
	public void winnerCanBeRevisedAgainWithoutRevivingOtherBranch() throws Exception {
		Order root = imported();
		ObjectNode first = branch(root, "posta_b_" + sourceOrigin, 1, 10, false);
		ObjectNode second = branch(root, "posta_c_" + sourceOrigin, 1, 20, false);
		receive().receiveOrder(first.toString());
		receive().receiveOrder(second.toString());
		ObjectNode descendant = branch(saved(second), "posta_c_" + sourceOrigin, 2, 30, false);
		receive().receiveOrder(descendant.toString());
		assertTrue(saved(first).getVoided());
		assertFalse(saved(descendant).getVoided());
		assertEquals(base.plusSeconds(29).toEpochMilli(), saved(second).getDateStopped().getTime());
	}

	@Test
	public void originBreaksEqualTimestampTie() throws Exception {
		Order root = imported();
		ObjectNode a = branch(root, "posta_b_" + sourceOrigin, 1, 10, false);
		ObjectNode b = branch(root, "posta_c_" + sourceOrigin, 1, 10, false);
		receive().receiveOrder(b.toString());
		receive().receiveOrder(a.toString());
		assertTrue(saved(a).getVoided());
		assertFalse(saved(b).getVoided());
	}

	@Test
	public void rollbackRestoresWholeChainAndReceipt() throws Exception {
		Order root = imported();
		ObjectNode first = branch(root, "posta_b_" + sourceOrigin, 1, 10, false);
		ObjectNode second = branch(root, "posta_c_" + sourceOrigin, 1, 20, false);
		receive().receiveOrder(first.toString());
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		try {
			receive().receiveOrder(second.toString());
			assertTrue(saved(first).getVoided());
			TestTransaction.flagForRollback();
			TestTransaction.end();
			TestTransaction.start();
			assertFalse(saved(first).getVoided());
			assertNull(saved(second));
			assertEquals(0, receive().getConfirmedOrderSequence("posta_c_" + sourceOrigin));
			assertEquals(base.plusSeconds(9).toEpochMilli(), fresh(root).getDateStopped().getTime());
			receive().receiveOrder(second.toString());
			assertTrue(saved(first).getVoided());
		}
		finally {
			if (!TestTransaction.isActive())
				TestTransaction.start();
		}
	}

	@Test public void clinicallyVoidedEncounterIsNeverReopened() throws Exception {
        Order root = imported();
        ObjectNode first = branch(root, "posta_b_" + sourceOrigin, 1, 10, false);
        ObjectNode second = branch(root, "posta_c_" + sourceOrigin, 1, 20, false);
        receive().receiveOrder(first.toString());
        Context.getEncounterService().voidEncounter(root.getEncounter(), "Anulacion clinica");
        assertThrows(APIException.class, () -> receive().receiveOrder(second.toString()));
        assertTrue(fresh(root).getEncounter().getVoided());
        assertEquals(0, receive().getConfirmedOrderSequence("posta_c_" + sourceOrigin));
    }

	@Test
	public void fulfillmentOfSupersededBranchIsKeptWithoutReactivatingIt() throws Exception {
		Order root = imported();
		ObjectNode first = branch(root, "posta_b_" + sourceOrigin, 1, 10, false);
		ObjectNode second = branch(root, "posta_c_" + sourceOrigin, 1, 20, false);
		receive().receiveOrder(first.toString());
		receive().receiveOrder(second.toString());
		receive().receiveOrder(change(saved(first), "posta_b_" + sourceOrigin, 2, 30, "COMPLETED").toString());
		assertTrue(saved(first).getVoided());
		assertEquals(Order.FulfillerStatus.COMPLETED, saved(first).getFulfillerStatus());
		assertFalse(saved(second).getVoided());
	}

	@Test
	public void drugRevisionConflictPreservesDosesAndSingleActiveBranch() throws Exception {
		Encounter encounter = new Encounter();
		encounter.setPatient(Context.getPatientService().getPatient(6));
		encounter.setEncounterType(Context.getEncounterService().getEncounterType(1));
		encounter.setEncounterDatetime(java.util.Date.from(base.minusSeconds(20)));
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
		drug.setDosingType(SimpleDosingInstructions.class);
		receive().receiveOrder(
		    new OrderCreationPayloadSerializer().serialize(drug, sourceOrigin, 1, UUID.randomUUID().toString(),
		        java.util.Date.from(base)));
		Order root = fresh(drug);
		ObjectNode first = branch(root, "posta_b_" + sourceOrigin, 1, 10, false);
		ObjectNode second = branch(root, "posta_c_" + sourceOrigin, 1, 20, false);
		receive().receiveOrder(second.toString());
		receive().receiveOrder(first.toString());
		assertTrue(saved(first).getVoided());
		assertFalse(saved(second).getVoided());
		assertEquals(325.0, ((DrugOrder) saved(second)).getDose());
		assertNull(saved(second).getDateStopped());
	}

	@Test public void missingDeletePrivilegeLeavesChainUntouched() throws Exception {
        Order root = imported();
        ObjectNode first = branch(root, "posta_b_" + sourceOrigin, 1, 10, false);
        ObjectNode second = branch(root, "posta_c_" + sourceOrigin, 1, 20, false);
        receive().receiveOrder(first.toString());
        String username = "oc" + UUID.randomUUID().toString().substring(0, 8);
        Role role = new Role(username);
        for (String name : Arrays.asList("Receive Synchronization Records", "View Synchronization Records",
            "Add Orders", "Get Orders", "Edit Orders", "Delete Orders", "Get Global Properties",
            "Get Patients", "Get People", "Get Encounters", "Get Concepts", "Get Providers",
            "Get Order Types", "Get Care Settings", "Get Order Frequencies")) {
            if (Context.getUserService().getPrivilege(name) == null)
                Context.getUserService().savePrivilege(new Privilege(name, "Prueba"));
        }
        for (Privilege privilege : Context.getUserService().getAllPrivileges()) {
            if (!"Delete Orders".equals(privilege.getPrivilege())) role.addPrivilege(privilege);
        }
        Context.getUserService().saveRole(role);
        User user = new User(); user.setPerson(Context.getPersonService().getPerson(2));
        user.setUsername(username); user.addRole(role);
        String password = UUID.randomUUID().toString() + "aA1!";
        Context.getUserService().createUser(user, password);
        org.openmrs.api.context.Credentials admin = getCredentials();
        OrderReceiveService service = receive(); Context.logout();
        try {
            Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(username, password));
            org.openmrs.api.APIAuthenticationException rejected = assertThrows(org.openmrs.api.APIAuthenticationException.class, () -> service.receiveOrder(second.toString()));
            if (!rejected.getMessage().contains("Delete Orders")) throw rejected;
        } finally { Context.logout(); Context.authenticate(admin); }
        assertFalse(saved(first).getVoided()); assertNull(saved(second));
        assertEquals(base.plusSeconds(9).toEpochMilli(), fresh(root).getDateStopped().getTime());
        assertEquals(0, receive().getConfirmedOrderSequence("posta_c_" + sourceOrigin));
        role.addPrivilege(Context.getUserService().getPrivilege("Delete Orders"));
        Context.getUserService().saveRole(role); Context.logout();
        try {
            Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(username, password));
            assertEquals(1, service.receiveOrder(second.toString()));
        } finally { Context.logout(); Context.authenticate(admin); }
        assertTrue(saved(first).getVoided()); assertFalse(saved(second).getVoided());
    }

	@Test
	public void threeConcurrentChangesConvergeAndKeepOriginalPayloads() throws Exception {
		Order root = imported();
		ObjectNode a = branch(root, "posta_b_" + sourceOrigin, 1, 10, false);
		ObjectNode b = branch(root, "posta_c_" + sourceOrigin, 1, 20, true);
		ObjectNode c = branch(root, "posta_d_" + sourceOrigin, 1, 30, false);
		receive().receiveOrder(b.toString());
		receive().receiveOrder(c.toString());
		receive().receiveOrder(a.toString());
		assertTrue(saved(a).getVoided());
		assertTrue(saved(b).getVoided());
		assertFalse(saved(c).getVoided());
		for (ObjectNode event : Arrays.asList(a, b, c)) {
			OrderIncomingEvent original = new OrderIncomingEvent(event.toString());
			assertEquals(event.toString(), sync().getOrderEventsAfter(original.origin, 0, 1).get(0).getPayloadJson());
		}
	}
}

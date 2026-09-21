package org.openmrs.module.synchronizationmr.api;

import java.sql.*;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.GlobalProperty;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class OrderPreparationIntegrationTest extends BaseModuleContextSensitiveTest {
	
	@BeforeEach public void prepare() throws Exception {
        new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(getConnection())).update("");
        Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
        try (Statement s = getConnection().createStatement()) {
            s.executeUpdate("delete from synchronizationmr_order_event");
            s.executeUpdate("delete from synchronizationmr_order_receipt");
            s.executeUpdate("update synchronizationmr_local_node set order_sequence = 0, server_id = 'testServer_1'");
        }
    }
	
	private OrderSyncService sync() {
		return Context.getService(OrderSyncService.class);
	}
	
	private long number(String sql) throws Exception {
        try (Statement s = getConnection().createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }
	
	@Test
	public void preparesExistingOrdersWithoutCreatingClinicalRowsOrPreparingPatients() throws Exception {
		long pending = sync().countOrdersPendingPreparation();
		long clinical = number("select count(*) from orders");
		long patients = number("select patient_sequence from synchronizationmr_local_node");
		assertTrue(pending > 1);
		assertEquals(1, sync().prepareExistingOrders(1));
		String snapshot = sync().getOrderEventsAfter("testServer_1", 0, 1).get(0).getPayloadJson();
		long prepared = 1;
		for (int i = 0; i < 100 && sync().countOrdersPendingPreparation() > 0; i++) {
			prepared += sync().prepareExistingOrders(2);
		}
		assertEquals(pending, prepared);
		assertEquals(0, sync().countOrdersPendingPreparation());
		assertEquals(0, sync().prepareExistingOrders(10));
		assertEquals(snapshot, sync().getOrderEventsAfter("testServer_1", 0, 1).get(0).getPayloadJson());
		assertEquals(clinical, number("select count(*) from orders"));
		assertEquals(patients, number("select patient_sequence from synchronizationmr_local_node"));
	}
	
	@Test
	public void preservesCommittedBatchAndResumesRolledBackBatch() {
		TestTransaction.flagForCommit();
		TestTransaction.end();
		try {
			long pending = sync().countOrdersPendingPreparation();
			assertEquals(1, sync().prepareExistingOrders(1));
			TestTransaction.start();
			assertEquals(1, sync().prepareExistingOrders(1));
			TestTransaction.flagForRollback();
			TestTransaction.end();
			assertEquals(pending - 1, sync().countOrdersPendingPreparation());
			assertEquals(1, sync().getHighestOrderSequence("testServer_1"));
			assertEquals(1, sync().prepareExistingOrders(1));
			assertEquals(2, sync().getHighestOrderSequence("testServer_1"));
		}
		finally {
			if (!TestTransaction.isActive()) {
				TestTransaction.start();
			}
		}
	}
	
	@Test public void refusesHistoricalMissingJsonAndInvalidLimits() throws Exception {
        assertThrows(APIException.class, () -> sync().prepareExistingOrders(0));
        assertThrows(APIException.class, () -> sync().prepareExistingOrders(101));
        sync().prepareExistingOrders(1);
        try (Statement s = getConnection().createStatement()) { s.executeUpdate("update synchronizationmr_order_event set payload_json = null"); }
        assertThrows(APIException.class, () -> sync().prepareExistingOrders(1));
        assertEquals(1, sync().getHighestOrderSequence("testServer_1"));
    }
	
	@Test public void preparesPredecessorBeforeDependentEvenWhenItsLocalIdIsHigher() throws Exception {
        try (Statement s = getConnection().createStatement()) {
            s.executeUpdate("update orders set previous_order_id = null, order_action = 'NEW' where order_id = 111");
            s.executeUpdate("update orders set previous_order_id = 111, order_action = 'REVISE' where order_id = 1");
        }
        Context.clearSession();
        long pending = sync().countOrdersPendingPreparation();
        long before = number("select count(*) from orders");
        for (int i = 0; i < 100 && sync().countOrdersPendingPreparation() > 0; i++) sync().prepareExistingOrders(2);
        assertEquals(0, sync().countOrdersPendingPreparation());
        assertEquals(pending, sync().getHighestOrderSequence("testServer_1"));
        long predecessor = number("select entity_sequence from synchronizationmr_order_event where order_id = 111");
        long dependent = number("select entity_sequence from synchronizationmr_order_event where order_id = 1");
        assertTrue(predecessor < dependent);
        assertEquals(before, number("select count(*) from orders"));
        assertEquals(111, number("select previous_order_id from orders where order_id = 1"));
        assertEquals(0, sync().prepareExistingOrders(2));
    }
	
	@Test public void dependencyCycleRemainsPendingAndIsNotReportedAsCompleted() throws Exception {
        try (Statement s = getConnection().createStatement()) {
            s.executeUpdate("update orders set previous_order_id = 111 where order_id = 1");
        }
        Context.clearSession();
        // Native fixture already has 111 -> 1; unrelated eligible orders can still be prepared.
        boolean blocked = false;
        for (int i = 0; i < 100; i++) {
            try {
                int prepared = sync().prepareExistingOrders(3);
                assertTrue(prepared > 0, "A blocked queue must not return a successful empty batch");
            } catch (APIException expected) { blocked = true; break; }
        }
        assertTrue(blocked); assertTrue(sync().countOrdersPendingPreparation() >= 2);
        assertEquals(0, number("select count(*) from synchronizationmr_order_event where order_id in (1,111)"));
        long sequence = sync().getHighestOrderSequence("testServer_1");
        assertThrows(APIException.class, () -> sync().prepareExistingOrders(1));
        assertEquals(sequence, sync().getHighestOrderSequence("testServer_1"));
    }
	
	@Test public void voidedUnpreparedPredecessorDoesNotAllowDependentToJumpAhead() throws Exception {
        try (Statement s = getConnection().createStatement()) {
            s.executeUpdate("update orders set voided = true where order_id = 1");
        }
        Context.clearSession();
        boolean blocked = false;
        for (int i = 0; i < 100; i++) {
            try { assertTrue(sync().prepareExistingOrders(3) > 0); }
            catch (APIException expected) { blocked = true; break; }
        }
        assertTrue(blocked);
        assertEquals(0, number("select count(*) from synchronizationmr_order_event where order_id = 111"));
        assertTrue(sync().countOrdersPendingPreparation() > 0);
    }
}

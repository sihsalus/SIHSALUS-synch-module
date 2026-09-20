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
}

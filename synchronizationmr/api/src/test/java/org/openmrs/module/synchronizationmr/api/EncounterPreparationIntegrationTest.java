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

public class EncounterPreparationIntegrationTest extends BaseModuleContextSensitiveTest {
	
	@BeforeEach public void prepare() throws Exception {
        new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(getConnection())).update("");
        Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
        try (Statement s = getConnection().createStatement()) {
            s.executeUpdate("delete from synchronizationmr_encounter_event");
            s.executeUpdate("delete from synchronizationmr_encounter_receipt");
            s.executeUpdate("update synchronizationmr_local_node set encounter_sequence = 0, server_id = 'testServer_1'");
        }
    }
	
	private EncounterSyncService sync() {
		return Context.getService(EncounterSyncService.class);
	}
	
	private long number(String sql) throws Exception {
        try (Statement s = getConnection().createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }
	
	@Test
	public void preparesExistingEncountersWithoutCreatingClinicalRowsOrPreparingPatients() throws Exception {
		long pending = sync().countEncountersPendingPreparation();
		long clinical = number("select count(*) from encounter");
		long patients = number("select patient_sequence from synchronizationmr_local_node");
		assertTrue(pending > 1);
		assertEquals(1, sync().prepareExistingEncounters(1));
		String snapshot = sync().getEncounterEventsAfter("testServer_1", 0, 1).get(0).getPayloadJson();
		long prepared = 1;
		for (int i = 0; i < 100 && sync().countEncountersPendingPreparation() > 0; i++) {
			prepared += sync().prepareExistingEncounters(2);
		}
		assertEquals(pending, prepared);
		assertEquals(0, sync().countEncountersPendingPreparation());
		assertEquals(0, sync().prepareExistingEncounters(10));
		assertEquals(snapshot, sync().getEncounterEventsAfter("testServer_1", 0, 1).get(0).getPayloadJson());
		assertEquals(clinical, number("select count(*) from encounter"));
		assertEquals(patients, number("select patient_sequence from synchronizationmr_local_node"));
	}
	
	@Test
	public void preservesCommittedBatchAndResumesRolledBackBatch() {
		TestTransaction.flagForCommit();
		TestTransaction.end();
		try {
			long pending = sync().countEncountersPendingPreparation();
			assertEquals(1, sync().prepareExistingEncounters(1));
			TestTransaction.start();
			assertEquals(1, sync().prepareExistingEncounters(1));
			TestTransaction.flagForRollback();
			TestTransaction.end();
			assertEquals(pending - 1, sync().countEncountersPendingPreparation());
			assertEquals(1, sync().getHighestEncounterSequence("testServer_1"));
			assertEquals(1, sync().prepareExistingEncounters(1));
			assertEquals(2, sync().getHighestEncounterSequence("testServer_1"));
		}
		finally {
			if (!TestTransaction.isActive()) {
				TestTransaction.start();
			}
		}
	}
	
	@Test public void refusesHistoricalMissingJsonAndInvalidLimits() throws Exception {
        assertThrows(APIException.class, () -> sync().prepareExistingEncounters(0));
        assertThrows(APIException.class, () -> sync().prepareExistingEncounters(101));
        sync().prepareExistingEncounters(1);
        try (Statement s = getConnection().createStatement()) { s.executeUpdate("update synchronizationmr_encounter_event set payload_json = null"); }
        assertThrows(APIException.class, () -> sync().prepareExistingEncounters(1));
        assertEquals(1, sync().getHighestEncounterSequence("testServer_1"));
    }
}

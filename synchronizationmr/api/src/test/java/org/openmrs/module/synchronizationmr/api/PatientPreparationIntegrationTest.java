package org.openmrs.module.synchronizationmr.api;

import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class PatientPreparationIntegrationTest extends BaseModuleContextSensitiveTest {
	
	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
        // Isolate this fixture from tests that commit synchronization rows in the shared H2 context.
        try (java.sql.Statement statement = getConnection().createStatement()) {
            // The standard seed contains unsupported attribute formats; test those explicitly below.
            statement.executeUpdate("delete from person_attribute");
            statement.executeUpdate("delete from synchronizationmr_patient_event");
            statement.executeUpdate("delete from synchronizationmr_patient_identity");
            statement.executeUpdate("delete from synchronizationmr_patient_receipt");
            statement.executeUpdate("update synchronizationmr_local_node set patient_sequence = 0, server_id = 'testServer_1'");
        }

	}
	
	@Test
    public void invalidPatientRollsBackEarlierPatientsInSameBatch() throws Exception {
        int second;
        try (java.sql.Statement statement = getConnection().createStatement();
                java.sql.ResultSet rows = statement.executeQuery("select p.patient_id from patient p join person pe on pe.person_id = p.patient_id where pe.voided = false order by p.patient_id")) {
            assertTrue(rows.next()); assertTrue(rows.next()); second = rows.getInt(1);
        }
        org.openmrs.PersonAttributeType type = new org.openmrs.PersonAttributeType();
        type.setName("Unsupported test format"); type.setDescription("Test"); type.setFormat("java.util.Date");
        Context.getPersonService().savePersonAttributeType(type);
        org.openmrs.Patient patient = Context.getPatientService().getPatient(second);
        patient.addAttribute(new org.openmrs.PersonAttribute(type, "unsupported"));
        Context.getPatientService().savePatient(patient);
        TestTransaction.flagForCommit(); TestTransaction.end();
        try {
            long pending = sync().countPatientsPendingPreparation();
            assertThrows(APIException.class, () -> sync().prepareExistingPatients(2));
            assertEquals(pending, sync().countPatientsPendingPreparation());
            assertEquals(0, sync().getHighestPatientSequence("testServer_1"));
        } finally { TestTransaction.start(); }
    }
	
	private PatientSyncService sync() {
		return Context.getService(PatientSyncService.class);
	}
	
	@Test
	public void batchesExistingPatientsWithoutDuplicatingOrRewritingSnapshots() {
		long pending = sync().countPatientsPendingPreparation();
		int clinicalCount = Context.getPatientService().getAllPatients().size();
		assertTrue(pending > 1);
		assertEquals(1, sync().prepareExistingPatients(1));
		assertEquals(pending - 1, sync().countPatientsPendingPreparation());
		String original = sync().getPatientEventsAfter("testServer_1", 0, 1).get(0).getPayloadJson();
		long prepared = 1;
		for (int attempts = 0; attempts < 100 && sync().countPatientsPendingPreparation() > 0; attempts++) {
			prepared += sync().prepareExistingPatients(2);
		}
		assertEquals(pending, prepared);
		assertEquals(0, sync().countPatientsPendingPreparation());
		assertEquals(0, sync().prepareExistingPatients(10));
		assertEquals(pending, sync().getHighestPatientSequence("testServer_1"));
		assertEquals(original, sync().getPatientEventsAfter("testServer_1", 0, 1).get(0).getPayloadJson());
		assertEquals(clinicalCount, Context.getPatientService().getAllPatients().size());
	}
	
	@Test
	public void resumesAfterRollbackWithoutLosingPreviouslyCommittedBatch() {
		TestTransaction.flagForCommit();
		TestTransaction.end();
		try {
			long pending = sync().countPatientsPendingPreparation();
			assertEquals(1, sync().prepareExistingPatients(1));
			TestTransaction.start();
			assertEquals(1, sync().prepareExistingPatients(1));
			TestTransaction.flagForRollback();
			TestTransaction.end();
			assertEquals(pending - 1, sync().countPatientsPendingPreparation());
			assertEquals(1, sync().getHighestPatientSequence("testServer_1"));
			assertEquals(1, sync().prepareExistingPatients(1));
			assertEquals(2, sync().getHighestPatientSequence("testServer_1"));
		}
		finally {
			if (!TestTransaction.isActive()) {
				TestTransaction.start();
			}
		}
	}
	
	@Test
    public void doesNotHideAnExistingEventWithoutPayload() throws Exception {
        sync().prepareExistingPatients(1);
        long before = sync().countPatientsPendingPreparation();
        try (java.sql.Statement s = getConnection().createStatement()) {
            s.executeUpdate("update synchronizationmr_patient_event set payload_json = null");
        }
        assertEquals(before + 1, sync().countPatientsPendingPreparation());
        assertThrows(APIException.class, () -> sync().prepareExistingPatients(1));
        assertEquals(1, sync().getHighestPatientSequence("testServer_1"));
    }
	
	@Test
    public void rejectsInvalidLimitsAndInterruptionWithoutPreparingPatients() {
        long pending = sync().countPatientsPendingPreparation();
        assertThrows(APIException.class, () -> sync().prepareExistingPatients(0));
        assertThrows(APIException.class, () -> sync().prepareExistingPatients(101));
        Thread.currentThread().interrupt();
        try { assertThrows(APIException.class, () -> sync().prepareExistingPatients(1)); }
        finally { Thread.interrupted(); }
        assertEquals(pending, sync().countPatientsPendingPreparation());
        assertEquals(0, sync().getHighestPatientSequence("testServer_1"));
    }
}

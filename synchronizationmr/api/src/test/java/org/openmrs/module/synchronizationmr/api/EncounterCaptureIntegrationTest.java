package org.openmrs.module.synchronizationmr.api;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Date;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.Encounter;
import org.openmrs.api.EncounterService;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.advice.EncounterCreationAdvice;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

/** Captura con OpenMRS y H2 reales; no utiliza HTTP ni prepara al paciente automáticamente. */
public class EncounterCaptureIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private EncounterCreationAdvice advice;
	
	private String payload(Integer encounterId) throws Exception {
        try (PreparedStatement query = getConnection().prepareStatement(
                "select payload_json from synchronizationmr_encounter_event where encounter_id = ?")) {
            query.setInt(1, encounterId);
            try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }
	
	@Test
	public void storesSnapshotAndKeepsItUnchangedAfterEditing() throws Exception {
		Encounter encounter = Context.getEncounterService().saveEncounter(sample());
		String original = payload(encounter.getEncounterId());
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(original);
		assertEquals(encounter.getUuid(), json.path("payload").path("encounterUuid").asText());
		assertEquals(encounter.getPatient().getUuid(), json.path("payload").path("patientUuid").asText());
		assertEquals(Context.getService(LocalNodeService.class).getLocalServerId(), json.path("originServerId").asText());
		encounter.setEncounterDatetime(new Date(1000));
		Context.getEncounterService().saveEncounter(encounter);
		Context.getService(EncounterSyncService.class).recordCreatedEncounter(encounter);
		assertEquals(original, payload(encounter.getEncounterId()));
		java.nio.file.Path directory = java.nio.file.Paths.get("target", "demo-sincronizacion");
		java.nio.file.Files.createDirectories(directory);
		mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("encuentro-creacion.json").toFile(), json);
		System.out.println("JSON DE ENCUENTRO VERIFICADO: identidad preservada y contenido inicial inmutable");
	}
	
	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new org.openmrs.GlobalProperty("server.id", "testServer_1"));
		advice = new EncounterCreationAdvice();
		Context.addAdvice(EncounterService.class, advice);
	}
	
	@AfterEach
	public void cleanup() {
		Context.removeAdvice(EncounterService.class, advice);
	}
	
	private Encounter sample() {
		Encounter encounter = new Encounter();
		encounter.setPatient(Context.getPatientService().getPatient(2));
		encounter.setEncounterType(Context.getEncounterService().getEncounterType(1));
		encounter.setLocation(Context.getLocationService().getLocation(1));
		encounter.setEncounterDatetime(new Date());
		return encounter;
	}
	
	private long number(String sql) throws Exception {
        try (PreparedStatement statement = getConnection().prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
	
	@Test public void capturesWithoutPatientSyncIdentityOrNetwork() throws Exception {
        String patientUuid = Context.getPatientService().getPatient(2).getUuid();
        assertNull(Context.getService(PatientSyncService.class).getByPatientUuid(patientUuid));
        long patientCounter = number("select patient_sequence from synchronizationmr_local_node where singleton_id = 1");
        Encounter encounter = Context.getEncounterService().saveEncounter(sample());
        try (PreparedStatement statement = getConnection().prepareStatement(
                "select encounter_uuid, patient_id, patient_uuid, origin_server_id, entity_sequence, state from synchronizationmr_encounter_event where encounter_id = ?")) {
            statement.setInt(1, encounter.getEncounterId());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(encounter.getUuid(), rows.getString(1));
                assertEquals(2, rows.getInt(2));
                assertEquals(patientUuid, rows.getString(3));
                assertEquals(Context.getService(LocalNodeService.class).getLocalServerId(), rows.getString(4));
                assertTrue(rows.getLong(5) > 0);
                assertEquals("PENDING", rows.getString(6));
            }
        }
        assertNull(Context.getService(PatientSyncService.class).getByPatientUuid(patientUuid));
        assertEquals(patientCounter, number("select patient_sequence from synchronizationmr_local_node where singleton_id = 1"));
        System.out.println("ENCUENTRO VERIFICADO: guardado local sin identidad de sincronización del paciente ni conexión de red");
    }
	
	@Test
	public void capturesTwoCreationsAndDoesNotDuplicateOnEditOrRepeatedCapture() throws Exception {
		long before = number("select encounter_sequence from synchronizationmr_local_node where singleton_id = 1");
		Encounter first = Context.getEncounterService().saveEncounter(sample());
		Context.getEncounterService().saveEncounter(sample());
		first.setEncounterDatetime(new Date());
		Context.getEncounterService().saveEncounter(first);
		Context.getService(EncounterSyncService.class).recordCreatedEncounter(first);
		assertEquals(before + 2,
		    number("select encounter_sequence from synchronizationmr_local_node where singleton_id = 1"));
		assertEquals(before + 2, number("select count(*) from synchronizationmr_encounter_event"));
	}
	
	@Test
	public void rollbackRemovesEncounterAndPendingEventTogether() throws Exception {
		long beforeCount = number("select count(*) from synchronizationmr_encounter_event");
		Encounter encounter = Context.getEncounterService().saveEncounter(sample());
		String uuid = encounter.getUuid();
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertNull(Context.getEncounterService().getEncounterByUuid(uuid));
		assertEquals(beforeCount, number("select count(*) from synchronizationmr_encounter_event"));
	}
	
	@Test
	public void oldEncounterEditDoesNotBecomeCreation() throws Exception {
		long beforeCount = number("select count(*) from synchronizationmr_encounter_event");
		Context.removeAdvice(EncounterService.class, advice);
		Encounter previous = Context.getEncounterService().saveEncounter(sample());
		Context.addAdvice(EncounterService.class, advice);
		Context.getEncounterService().saveEncounter(previous);
		assertEquals(beforeCount, number("select count(*) from synchronizationmr_encounter_event"));
	}
	
	@Test
	public void capturesWhenCallerHasNoOuterTransaction() throws Exception {
		long beforeCount = number("select count(*) from synchronizationmr_encounter_event");
		TestTransaction.flagForCommit();
		TestTransaction.end();
		try {
			Encounter encounter = Context.getEncounterService().saveEncounter(sample());
			assertNotNull(Context.getEncounterService().getEncounterByUuid(encounter.getUuid()));
			TestTransaction.start();
			assertEquals(beforeCount + 1, number("select count(*) from synchronizationmr_encounter_event"));
		}
		finally {
			if (!TestTransaction.isActive()) {
				TestTransaction.start();
			}
		}
	}
}

package org.openmrs.module.synchronizationmr.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.time.Instant;
import java.util.*;
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

/** Guardados reales, migraciÃ³n H2 y recepciÃ³n transaccional de cambios de metadatos. */
public class EncounterUpdateIntegrationTest extends BaseModuleContextSensitiveTest {
	
	@Test
	public void receivesTypeFormAndNewProviderAndExplicitFormRemoval() {
		Encounter source = sample();
		create(source, "a");
		source.setEncounterType(Context.getEncounterService().getEncounterType(2));
		source.setForm(Context.getFormService().getForm(1));
		assertNotNull(source.getForm());
		EncounterProvider provider = new EncounterProvider();
		provider.setProvider(Context.getProviderService().getProvider(1));
		provider.setEncounterRole(Context.getEncounterService().getEncounterRole(1));
		provider.setEncounter(source);
		source.getEncounterProviders().add(provider);
		receive().receiveEncounter(update(source, "a", 2, 10, "encounterTypeUuid", "formUuid", "encounterProviders"));
		Encounter saved = stored(source);
		assertEquals(source.getEncounterType().getUuid(), saved.getEncounterType().getUuid());
		assertEquals(source.getForm().getUuid(), saved.getForm().getUuid());
		assertEquals(provider.getUuid(), saved.getActiveEncounterProviders().iterator().next().getUuid());
		source.setForm(null);
		receive().receiveEncounter(update(source, "a", 3, 20, "formUuid"));
		assertNull(stored(source).getForm());
	}
	
	@Test public void concurrentReceiversConvergeAndConfirmBothOrigins() throws Exception {
        Encounter source = sample(); create(source, "creator");
        source.setLocation(null);
        String a = update(source, "a", 1, 10, "locationUuid");
        source.setLocation(Context.getLocationService().getLocation(2));
        String b = update(source, "b", 1, 20, "locationUuid");
        org.openmrs.api.context.Credentials credentials = getCredentials();
        TestTransaction.flagForCommit(); TestTransaction.end();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Long>> results = new ArrayList<>();
            for (String json : Arrays.asList(a, b)) {
                results.add(pool.submit(() -> {
                    Context.openSession();
                    try { Context.authenticate(credentials); start.await(); return receive().receiveEncounter(json); }
                    finally { Context.closeSession(); }
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<Long> result : results) assertEquals(1L, result.get(30, java.util.concurrent.TimeUnit.SECONDS).longValue());
        } finally { pool.shutdownNow(); TestTransaction.start(); }
        assertEquals(Integer.valueOf(2), stored(source).getLocation().getLocationId());
        assertEquals(localBefore, sync().getHighestEncounterSequence("testServer_1"));
        assertEquals(1, receive().getConfirmedEncounterSequence(prefix + "a"));
        assertEquals(1, receive().getConfirmedEncounterSequence(prefix + "b"));
    }
	
	private final EncounterCreationAdvice advice = new EncounterCreationAdvice();
	
	private final ObservationAdditionAdvice obsAdvice = new ObservationAdditionAdvice();
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private final String prefix = "enc_" + UUID.randomUUID().toString().replace("-", "") + "_";
	
	private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
	
	private long localBefore;
	
	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		Context.addAdvice(EncounterService.class, advice);
		Context.addAdvice(ObsService.class, obsAdvice);
		localBefore = sync().getHighestEncounterSequence("testServer_1");
	}
	
	@AfterEach
	public void cleanup() {
		Context.removeAdvice(EncounterService.class, advice);
		Context.removeAdvice(ObsService.class, obsAdvice);
	}
	
	private EncounterSyncService sync() {
		return Context.getService(EncounterSyncService.class);
	}
	
	private EncounterReceiveService receive() {
		return Context.getService(EncounterReceiveService.class);
	}
	
	private Encounter sample() {
		Encounter e = new Encounter();
		e.setPatient(Context.getPatientService().getPatient(2));
		e.setEncounterType(Context.getEncounterService().getEncounterType(1));
		e.setLocation(Context.getLocationService().getLocation(1));
		e.setEncounterDatetime(java.util.Date.from(BASE.minusSeconds(100)));
		return e;
	}
	
	private Obs observation(Encounter e) {
		Obs obs = new Obs();
		obs.setPerson(e.getPatient());
		obs.setEncounter(e);
		obs.setConcept(Context.getConceptService().getConcept(5089));
		obs.setObsDatetime(e.getEncounterDatetime());
		obs.setLocation(e.getLocation());
		obs.setValueNumeric(65.0);
		return obs;
	}
	
	private String create(Encounter source, String origin) {
		String json = new EncounterCreationPayloadSerializer().serialize(source, prefix + origin, 1, UUID.randomUUID()
		        .toString(), java.util.Date.from(BASE));
		assertEquals(1, receive().receiveEncounter(json));
		return json;
	}
	
	private String update(Encounter source, String origin, long seq, int seconds, String... groups) {
        return EncounterUpdateEvent.create(EncounterUpdateEvent.capture(source), new LinkedHashSet<>(Arrays.asList(groups)), prefix + origin, seq, BASE.plusSeconds(seconds));
    }
	
	private Encounter stored(Encounter source) {
		Context.flushSession();
		Context.clearSession();
		return Context.getEncounterService().getEncounterByUuid(source.getUuid());
	}
	
	private long count(String sql) throws Exception {
        try (Statement q = getConnection().createStatement(); ResultSet rows = q.executeQuery(sql)) { rows.next(); return rows.getLong(1); }
    }
	
	@Test
	public void acceptsTechnicalRoleWithEditPermission() throws Exception {
		technicalRole(true);
	}
	
	@Test
	public void rejectsTechnicalRoleWithoutEditPermission() throws Exception {
		technicalRole(false);
	}
	
	private void technicalRole(boolean edit) throws Exception {
        Encounter source = sample(); create(source, "a");
        source.setLocation(Context.getLocationService().getLocation(2));
        String json = update(source, "a", 2, 10, "locationUuid");
        Role role = new Role(prefix);
        List<String> privileges = new ArrayList<>(Arrays.asList("Receive Synchronization Records", "View Synchronization Records",
            "Get Encounters", "Get Observations", "Add Encounters", "Add Observations", "Get Patients", "Get People",
            "Get Global Properties", "Get Locations", "Get Encounter Types", "Get Forms", "Get Providers", "Get Encounter Roles"));
        if (edit) privileges.add("Edit Encounters");
        for (String name : privileges) {
            Privilege privilege = Context.getUserService().getPrivilege(name);
            if (privilege == null) privilege = Context.getUserService().savePrivilege(new Privilege(name, "Prueba"));
            role.addPrivilege(privilege);
        }
        role = Context.getUserService().saveRole(role);
        User user = new User(); user.setPerson(Context.getPersonService().getPerson(2)); user.setUsername(prefix);
        user.addRole(role); Context.getUserService().createUser(user, "Test-Encounter123!");
        org.openmrs.api.context.Credentials admin = getCredentials();
        EncounterReceiveService receiver = receive();
        Context.logout();
        try {
            Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(prefix, "Test-Encounter123!"));
            if (edit) assertEquals(2, receiver.receiveEncounter(json));
            else assertThrows(org.openmrs.api.APIAuthenticationException.class, () -> receiver.receiveEncounter(json));
        } finally { Context.logout(); Context.authenticate(admin); }
        assertEquals(edit ? 2 : 1, receive().getConfirmedEncounterSequence(prefix + "a"));
        assertEquals(Integer.valueOf(edit ? 2 : 1), stored(source).getLocation().getLocationId());
    }
	
	@Test public void nativeValidationFailureRollsBackUpdateAndReceipt() throws Exception {
        Encounter source = sample(); create(source, "a");
        source.setEncounterDatetime(java.util.Date.from(Instant.now().plusSeconds(86400)));
        String json = update(source, "a", 2, 10, "encounterDatetime");
        TestTransaction.flagForCommit(); TestTransaction.end();
        try {
            assertThrows(APIException.class, () -> receive().receiveEncounter(json));
            assertEquals(1, receive().getConfirmedEncounterSequence(prefix + "a"));
            TestTransaction.start(); Context.clearSession();
            assertEquals(java.util.Date.from(BASE.minusSeconds(100)), Context.getEncounterService().getEncounterByUuid(source.getUuid()).getEncounterDatetime());
            assertEquals(0, count("select count(*) from synchronizationmr_encounter_update where origin_server_id='" + prefix + "a'"));
        } finally { if (!TestTransaction.isActive()) TestTransaction.start(); }
    }
	
	@Test public void rejectsProviderAssociationOwnedByAnotherEncounter() throws Exception {
        Encounter first = sample();
        EncounterProvider provider = new EncounterProvider();
        provider.setProvider(Context.getProviderService().getProvider(1));
        provider.setEncounterRole(Context.getEncounterService().getEncounterRole(1));
        provider.setEncounter(first); first.getEncounterProviders().add(provider);
        create(first, "a");
        Encounter second = sample(); create(second, "b");
        second.getEncounterProviders().add(provider);
        String json = update(second, "b", 2, 10, "encounterProviders");
        assertThrows(APIException.class, () -> receive().receiveEncounter(json));
        assertEquals(1, receive().getConfirmedEncounterSequence(prefix + "b"));
        assertTrue(stored(second).getActiveEncounterProviders().isEmpty());
    }
	
	@Test
	public void capturesLocalUpdateAndSkipsNoopAfterReloadWithoutChangingCreation() throws Exception {
		Encounter e = Context.getEncounterService().saveEncounter(sample());
		String creation = sync().getEncounterEventsAfter("testServer_1", localBefore, 1).get(0).getPayloadJson();
		e.setLocation(Context.getLocationService().getLocation(2));
		Context.getEncounterService().saveEncounter(e);
		EncounterUpdateEvent update = new EncounterUpdateEvent(sync()
		        .getEncounterEventsAfter("testServer_1", localBefore + 1, 1).get(0).getPayloadJson());
		assertEquals(Collections.singleton("locationUuid"), update.changedGroups);
		assertEquals(e.getUuid(), update.encounterUuid);
		assertEquals(creation, sync().getEncounterEventsAfter("testServer_1", localBefore, 1).get(0).getPayloadJson());
		e = stored(e);
		Context.getEncounterService().saveEncounter(e);
		assertEquals(localBefore + 2, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void interleavesCreateUpdateAndAdditionInOneStream() throws Exception {
		Encounter e = Context.getEncounterService().saveEncounter(sample());
		e.setLocation(Context.getLocationService().getLocation(2));
		e.addObs(observation(e));
		Context.getEncounterService().saveEncounter(e);
		List<EncounterSyncEvent> events = sync().getEncounterEventsAfter("testServer_1", localBefore, 100);
		assertEquals(3, events.size());
		String[] operations = { "CREATE", "UPDATE", "ADD_OBS" };
		for (int i = 0; i < 3; i++) {
			assertEquals(localBefore + i + 1, events.get(i).getSequence());
			assertEquals(operations[i], mapper.readTree(events.get(i).getPayloadJson()).path("operation").asText());
		}
	}
	
	@Test
	public void receivesUpdateIdempotentlyWithoutEchoAndAllowsSubsequentLocalEdit() throws Exception {
		Encounter source = sample();
		String creation = create(source, "a");
		source.setLocation(Context.getLocationService().getLocation(2));
		String event = update(source, "a", 2, 10, "locationUuid");
		assertEquals(2, receive().receiveEncounter(event));
		assertEquals(2, receive().receiveEncounter(event));
		Encounter saved = stored(source);
		assertEquals(source.getLocation().getUuid(), saved.getLocation().getUuid());
		assertEquals(localBefore, sync().getHighestEncounterSequence("testServer_1"));
		assertEquals(creation, sync().getEncounterEventsAfter(prefix + "a", 0, 1).get(0).getPayloadJson());
		assertEquals(event, sync().getEncounterEventsAfter(prefix + "a", 1, 1).get(0).getPayloadJson());
		Context.getEncounterService().saveEncounter(saved);
		assertEquals(localBefore, sync().getHighestEncounterSequence("testServer_1"));
		saved.setLocation(Context.getLocationService().getLocation(1));
		Context.getEncounterService().saveEncounter(saved);
		assertEquals(localBefore + 1, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void preservesUneditedFieldsAndRejectsLateOverwrite() {
		Encounter source = sample();
		create(source, "a");
		source.setLocation(Context.getLocationService().getLocation(2));
		receive().receiveEncounter(update(source, "b", 1, 20, "locationUuid"));
		source.setLocation(Context.getLocationService().getLocation(1));
		source.setEncounterDatetime(java.util.Date.from(BASE.minusSeconds(50)));
		receive().receiveEncounter(update(source, "a", 2, 10, "encounterDatetime", "locationUuid"));
		Encounter saved = stored(source);
		assertEquals(Integer.valueOf(2), saved.getLocation().getLocationId());
		assertEquals(source.getEncounterDatetime(), saved.getEncounterDatetime());
		assertEquals(2, receive().getConfirmedEncounterSequence(prefix + "a"));
		assertEquals(1, receive().getConfirmedEncounterSequence(prefix + "b"));
	}
	
	@Test
	public void equalTimestampUsesOriginRegardlessOfArrivalOrder() {
		Encounter first = sample(), second = sample();
		create(first, "first");
		create(second, "second");
		first.setLocation(Context.getLocationService().getLocation(2));
		second.setLocation(Context.getLocationService().getLocation(2));
		String firstZ = update(first, "z", 1, 10, "locationUuid");
		String secondZ = update(second, "z", 2, 10, "locationUuid");
		first.setLocation(null);
		second.setLocation(null);
		String firstA = update(first, "a", 1, 10, "locationUuid");
		String secondA = update(second, "a", 2, 10, "locationUuid");
		receive().receiveEncounter(firstZ);
		receive().receiveEncounter(firstA);
		receive().receiveEncounter(secondA);
		receive().receiveEncounter(secondZ);
		assertEquals(Integer.valueOf(2), stored(first).getLocation().getLocationId());
		assertEquals(Integer.valueOf(2), stored(second).getLocation().getLocationId());
	}
	
	@Test
	public void metadataReceptionPreservesObservationUuidValueDateAndCount() throws Exception {
		Encounter source = sample();
		Obs original = observation(source);
		source.addObs(original);
		create(source, "a");
		long obsCount = count("select count(*) from obs");
		source.setLocation(Context.getLocationService().getLocation(2));
		source.setEncounterDatetime(java.util.Date.from(BASE.minusSeconds(50)));
		receive().receiveEncounter(update(source, "a", 2, 10, "encounterDatetime", "locationUuid"));
		stored(source);
		Obs actual = Context.getObsService().getObsByUuid(original.getUuid());
		assertEquals(65.0, actual.getValueNumeric());
		assertEquals(original.getObsDatetime(), actual.getObsDatetime());
		assertEquals(original.getLocation().getUuid(), actual.getLocation().getUuid());
		assertFalse(actual.getVoided());
		assertEquals(obsCount, count("select count(*) from obs"));
	}
	
	@Test
	public void receivesProviderRemovalWithoutDeletingHistoryAndRestoresSameUuid() {
		Encounter source = sample();
		EncounterProvider professional = new EncounterProvider();
		professional.setProvider(Context.getProviderService().getProvider(1));
		professional.setEncounterRole(Context.getEncounterService().getEncounterRole(1));
		professional.setEncounter(source);
		source.getEncounterProviders().add(professional);
		create(source, "a");
		professional.setVoided(true);
		receive().receiveEncounter(update(source, "a", 2, 10, "encounterProviders"));
		Encounter saved = stored(source);
		assertEquals(0, saved.getActiveEncounterProviders().size());
		assertEquals(1, saved.getEncounterProviders().size());
		professional.setVoided(false);
		receive().receiveEncounter(update(source, "a", 3, 20, "encounterProviders"));
		saved = stored(source);
		assertEquals(1, saved.getActiveEncounterProviders().size());
		assertEquals(professional.getUuid(), saved.getActiveEncounterProviders().iterator().next().getUuid());
		assertEquals(1, saved.getEncounterProviders().size());
	}
	
	@Test public void rejectsWrongPatientGapsMissingDependenciesAndAlteredReplay() throws Exception {
        Encounter source = sample(); create(source, "a");
        source.setLocation(Context.getLocationService().getLocation(2));
        String valid = update(source, "a", 2, 10, "locationUuid");
        ObjectNode wrong = (ObjectNode) mapper.readTree(valid);
        ((ObjectNode) wrong.get("payload")).put("patientUuid", UUID.randomUUID().toString());
        assertThrows(APIException.class, () -> receive().receiveEncounter(wrong.toString()));
        ObjectNode missing = (ObjectNode) mapper.readTree(valid);
        ((ObjectNode) missing.get("payload")).put("locationUuid", UUID.randomUUID().toString());
        assertThrows(APIException.class, () -> receive().receiveEncounter(missing.toString()));
        assertThrows(APIException.class, () -> receive().receiveEncounter(update(source, "a", 3, 20, "locationUuid")));
        assertEquals(1, receive().getConfirmedEncounterSequence(prefix + "a"));
        assertEquals(Integer.valueOf(1), stored(source).getLocation().getLocationId());
        receive().receiveEncounter(valid);
        ((ObjectNode) wrong.get("payload")).put("patientUuid", source.getPatient().getUuid());
        ((ObjectNode) wrong.get("payload")).putNull("locationUuid");
        assertThrows(APIException.class, () -> receive().receiveEncounter(wrong.toString()));
    }
	
	@Test public void missingEncounterDefersOriginWithoutConfirmingIt() {
        Encounter source = sample();
        String event = update(source, "a", 1, 10, "locationUuid");
        assertThrows(EncounterDependencyException.class, () -> receive().receiveEncounter(event));
        assertEquals(0, receive().getConfirmedEncounterSequence(prefix + "a"));
        create(source, "b");
        assertEquals(1, receive().receiveEncounter(event));
    }
	
	@Test
	public void localRollbackUndoesClinicalChangeEventStateAndCounter() throws Exception {
		Encounter e = Context.getEncounterService().saveEncounter(sample());
		String id = e.getUuid();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		e = Context.getEncounterService().getEncounterByUuid(id);
		e.setLocation(Context.getLocationService().getLocation(2));
		Context.getEncounterService().saveEncounter(e);
		assertEquals(localBefore + 2, sync().getHighestEncounterSequence("testServer_1"));
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		Context.clearSession();
		assertEquals(Integer.valueOf(1), Context.getEncounterService().getEncounterByUuid(id).getLocation().getLocationId());
		assertEquals(localBefore + 1, sync().getHighestEncounterSequence("testServer_1"));
		assertEquals(0,
		    count("select count(*) from synchronizationmr_encounter_state where encounter_id=" + e.getEncounterId()));
	}
	
	@Test
	public void preparedHistoricalEncounterStartsWithLatestMetadataThenCapturesUpdate() throws Exception {
		Encounter source = sample();
		Context.removeAdvice(EncounterService.class, advice);
		try {
			Context.getEncounterService().saveEncounter(source);
			source.setLocation(Context.getLocationService().getLocation(2));
			Context.getEncounterService().saveEncounter(source);
		}
		finally {
			Context.addAdvice(EncounterService.class, advice);
		}
		assertEquals(localBefore, sync().getHighestEncounterSequence("testServer_1"));
		sync().recordCreatedEncounter(source);
		String original = sync().getEncounterEventsAfter("testServer_1", localBefore, 1).get(0).getPayloadJson();
		assertEquals(source.getLocation().getUuid(), mapper.readTree(original).path("payload").path("locationUuid").asText());
		source.setLocation(Context.getLocationService().getLocation(1));
		Context.getEncounterService().saveEncounter(source);
		assertEquals(localBefore + 2, sync().getHighestEncounterSequence("testServer_1"));
	}
}

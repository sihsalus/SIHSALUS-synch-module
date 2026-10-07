package org.openmrs.module.synchronizationmr.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.*;
import org.openmrs.Order;
import org.openmrs.api.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.advice.*;
import org.openmrs.module.synchronizationmr.sync.*;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class EncounterVoidIntegrationTest extends BaseModuleContextSensitiveTest {

	@Test
	public void voidsGroupsAndPreservesPreviouslyVoidedValues() {
		Encounter source = sample();
		Obs group = obs(source, 0);
		group.setValueNumeric(null);
		group.setConcept(Context.getConceptService().getConcept(23));
		Obs member = obs(source, 61);
		group.addGroupMember(member);
		source.addObs(group);
		Obs old = obs(source, 60);
		source.addObs(old);
		receive().receiveEncounter(
		    new EncounterCreationPayloadSerializer().serialize(source, origin, 1, UUID.randomUUID().toString(),
		        Date.from(base)));
		Encounter e = Context.getEncounterService().getEncounterByUuid(source.getUuid());
		Context.getObsService().voidObs(Context.getObsService().getObsByUuid(old.getUuid()), "Anulacion anterior");
		receive().receiveEncounter(event(e));
		stored(e.getUuid());
		assertTrue(Context.getObsService().getObsByUuid(group.getUuid()).getVoided());
		assertTrue(Context.getObsService().getObsByUuid(member.getUuid()).getVoided());
		assertEquals(61, Context.getObsService().getObsByUuid(member.getUuid()).getValueNumeric());
		assertEquals("Anulacion anterior", Context.getObsService().getObsByUuid(old.getUuid()).getVoidReason());
	}

	@Test
	public void nativeCascadeAlsoVoidsExistingOrderWithoutPurgingIt() {
		Encounter e = imported();
		Order order = new Order();
		order.setPatient(e.getPatient());
		order.setEncounter(e);
		order.setConcept(Context.getConceptService().getConcept(5089));
		order.setOrderType(Context.getOrderService().getOrderType(17));
		order.setOrderer(Context.getProviderService().getProvider(1));
		order.setCareSetting(Context.getOrderService().getCareSetting(1));
		order.setDateActivated(Date.from(base.plusSeconds(1)));
		order.setInstructions("Orden ficticia para cascada");
		Context.getOrderService().saveRetrospectiveOrder(order, null);
		String id = order.getUuid();
		e = stored(e.getUuid());
		receive().receiveEncounter(event(e));
		stored(e.getUuid());
		Order saved = Context.getOrderService().getOrderByUuid(id);
		assertNotNull(saved);
		assertTrue(saved.getVoided());
		assertEquals("Orden ficticia para cascada", saved.getInstructions());
	}

	private final EncounterCreationAdvice encounterAdvice = new EncounterCreationAdvice();

	private final ObservationAdditionAdvice obsAdvice = new ObservationAdditionAdvice();

	private final ObjectMapper m = new ObjectMapper();

	private final String origin = "annul_" + UUID.randomUUID().toString().replace("-", "");

	private final Instant base = Instant.parse("2026-01-01T00:00:00Z");

	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		Context.addAdvice(EncounterService.class, encounterAdvice);
		Context.addAdvice(ObsService.class, obsAdvice);
	}

	@AfterEach
	public void clean() {
		Context.removeAdvice(EncounterService.class, encounterAdvice);
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
		e.setEncounterDatetime(Date.from(base));
		return e;
	}

	private Obs obs(Encounter e, double value) {
		Obs o = new Obs();
		o.setPerson(e.getPatient());
		o.setEncounter(e);
		o.setConcept(Context.getConceptService().getConcept(5089));
		o.setLocation(e.getLocation());
		o.setObsDatetime(e.getEncounterDatetime());
		o.setValueNumeric(value);
		return o;
	}

	private Encounter imported() {
		Encounter e = sample();
		e.addObs(obs(e, 62));
		receive().receiveEncounter(
		    new EncounterCreationPayloadSerializer().serialize(e, origin, 1, UUID.randomUUID().toString(), Date.from(base)));
		return Context.getEncounterService().getEncounterByUuid(e.getUuid());
	}

	private String event(Encounter encounter) {
		Encounter snapshot = new Encounter();
		snapshot.setUuid(encounter.getUuid());
		snapshot.setPatient(encounter.getPatient());
		snapshot.setVoidReason("Anulacion ficticia");
		snapshot.setDateVoided(Date.from(base.plusSeconds(20)));
		return EncounterVoidEvent.create(snapshot, origin, 2, base.plusSeconds(20));
	}

	private Encounter stored(String uuid) {
		Context.flushSession();
		Context.clearSession();
		return Context.getEncounterService().getEncounterByUuid(uuid);
	}

	@Test
	public void localVoidPublishesOnceAndKeepsHistory() throws Exception {
		Encounter e = imported();
		String id = e.getUuid();
		String obs = e.getObs().iterator().next().getUuid();
		long before = sync().getHighestEncounterSequence("testServer_1");
		Context.getEncounterService().voidEncounter(e, "Registro ficticio retirado");
		Context.getEncounterService().voidEncounter(e, "Registro ficticio retirado");
		List<EncounterSyncEvent> events = sync().getEncounterEventsAfter("testServer_1", before, 100);
		assertEquals(1, events.size());
		EncounterVoidEvent parsed = new EncounterVoidEvent(events.get(0).getPayloadJson());
		assertEquals(id, parsed.encounterUuid);
		assertEquals("Registro ficticio retirado", parsed.reason);
		assertTrue(stored(id).getVoided());
		assertTrue(Context.getObsService().getObsByUuid(obs).getVoided());
		assertEquals(62, Context.getObsService().getObsByUuid(obs).getValueNumeric());
	}

	@Test
	public void neverPublishedEncounterRemainsLocalAndIsNotPrepared() {
		Context.removeAdvice(EncounterService.class, encounterAdvice);
		Encounter e = sample();
		e.addObs(obs(e, 62));
		Context.getEncounterService().saveEncounter(e);
		Context.addAdvice(EncounterService.class, encounterAdvice);
		long pending = sync().countEncountersPendingPreparation();
		long before = sync().getHighestEncounterSequence("testServer_1");
		Context.getEncounterService().voidEncounter(e, "Prueba local sin publicar");
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
		assertEquals(pending - 1, sync().countEncountersPendingPreparation());
		assertTrue(stored(e.getUuid()).getVoided());
	}

	@Test
	public void receivesAndReplaysWithoutEchoOrDeletingValues() {
		Encounter e = imported();
		String obs = e.getObs().iterator().next().getUuid();
		String json = event(e);
		long before = sync().getHighestEncounterSequence("testServer_1");
		assertEquals(2, receive().receiveEncounter(json));
		assertEquals(2, receive().receiveEncounter(json));
		Encounter saved = stored(e.getUuid());
		assertTrue(saved.getVoided());
		assertEquals("Anulacion ficticia", saved.getVoidReason());
		assertEquals(Date.from(base.plusSeconds(20)), saved.getDateVoided());
		assertEquals(62, Context.getObsService().getObsByUuid(obs).getValueNumeric());
		assertTrue(Context.getObsService().getObsByUuid(obs).getVoided());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
		assertEquals(json, sync().getEncounterEventsAfter(origin, 1, 100).get(0).getPayloadJson());
	}

	@Test public void rejectsAlteredReplay() throws Exception {
        Encounter e=imported();String json=event(e);receive().receiveEncounter(json);
        ObjectNode altered=(ObjectNode)m.readTree(json);((ObjectNode)altered.path("payload")).put("reason","Otra razon");
        assertThrows(APIException.class,()->receive().receiveEncounter(altered.toString()));
        assertEquals(2,receive().getConfirmedEncounterSequence(origin));
    }

	@Test public void waitsForMissingEncounterWithoutAdvancingReceipt() throws Exception {
        Encounter e=imported();ObjectNode json=(ObjectNode)m.readTree(event(e));
        ((ObjectNode)json.path("payload")).put("encounterUuid",UUID.randomUUID().toString());
        assertThrows(EncounterDependencyException.class,()->receive().receiveEncounter(json.toString()));
        assertEquals(1,receive().getConfirmedEncounterSequence(origin));
    }

	@Test public void rejectsForeignPatientWithoutChangingEncounter() throws Exception {
        Encounter e=imported();ObjectNode json=(ObjectNode)m.readTree(event(e));
        ((ObjectNode)json.path("payload")).put("patientUuid",UUID.randomUUID().toString());
        assertThrows(APIException.class,()->receive().receiveEncounter(json.toString()));
        assertEquals(1,receive().getConfirmedEncounterSequence(origin));assertFalse(stored(e.getUuid()).getVoided());
    }

	@Test
	public void localRollbackRestoresEncounterObservationsAndSequence() {
		Encounter e = imported();
		String id = e.getUuid();
		String obs = e.getObs().iterator().next().getUuid();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		long before = sync().getHighestEncounterSequence("testServer_1");
		Context.getEncounterService().voidEncounter(Context.getEncounterService().getEncounterByUuid(id),
		    "Prueba de rollback");
		assertEquals(before + 1, sync().getHighestEncounterSequence("testServer_1"));
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertFalse(stored(id).getVoided());
		assertFalse(Context.getObsService().getObsByUuid(obs).getVoided());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
	}

	@Test
	public void receiveRollbackRestoresEncounterAndReceipt() {
		Encounter e = imported();
		String json = event(e);
		String id = e.getUuid();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		receive().receiveEncounter(json);
		assertTrue(stored(id).getVoided());
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertFalse(stored(id).getVoided());
		assertEquals(1, receive().getConfirmedEncounterSequence(origin));
		assertEquals(2, receive().receiveEncounter(json));
	}

	@Test public void invalidContractNeverReachesNativeDeletion() throws Exception {
        Encounter e=imported();ObjectNode json=(ObjectNode)m.readTree(event(e));
        json.put("schemaVersion",Long.MAX_VALUE);
        assertThrows(APIException.class,()->new EncounterIncomingEvent(json.toString()));
        json.put("schemaVersion",10);((ObjectNode)json.path("payload")).put("purge",true);
        assertThrows(APIException.class,()->new EncounterIncomingEvent(json.toString()));
        ((ObjectNode)json.path("payload")).remove("purge");((ObjectNode)json.path("payload")).put("reason"," ");
        assertThrows(APIException.class,()->new EncounterIncomingEvent(json.toString()));
        assertFalse(stored(e.getUuid()).getVoided());
    }

	@Test
	public void receivesWithTechnicalRole() {
		technicalRole(true);
	}

	@Test
	public void rejectsTechnicalRoleWithoutDeletePrivilege() {
		technicalRole(false);
	}

	private void technicalRole(boolean delete) {
        Encounter e=imported();String json=event(e);Role role=new Role(origin);
        List<String> privileges=new ArrayList<>(Arrays.asList("Receive Synchronization Records","View Synchronization Records","Get Encounters","Get Observations","Add Encounters","Add Observations","Edit Encounters","Edit Observations","Get Patients","Get People","Get Global Properties","Get Locations","Get Encounter Types","Get Forms","Get Providers","Get Encounter Roles","Get Concepts","Get Orders","Delete Observations","Delete Orders"));
        if(delete) privileges.add("Delete Encounters");
        for(String name:privileges) { Privilege p=Context.getUserService().getPrivilege(name);if(p==null)p=Context.getUserService().savePrivilege(new Privilege(name,"Prueba"));role.addPrivilege(p); }
        role=Context.getUserService().saveRole(role);User user=new User();user.setPerson(Context.getPersonService().getPerson(2));user.setUsername(origin);user.addRole(role);Context.getUserService().createUser(user,"Test-Annul123!");
        org.openmrs.api.context.Credentials admin=getCredentials();EncounterReceiveService receiver=receive();Context.logout();
        try {
            Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(origin,"Test-Annul123!"));
            if(delete)assertEquals(2,receiver.receiveEncounter(json));else assertThrows(APIAuthenticationException.class,()->receiver.receiveEncounter(json));
        } finally { Context.logout();Context.authenticate(admin); }
        assertEquals(delete?2:1,receive().getConfirmedEncounterSequence(origin));assertEquals(delete,stored(e.getUuid()).getVoided());
    }
}

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
import org.openmrs.api.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.advice.*;
import org.openmrs.module.synchronizationmr.sync.*;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class ObservationVoidIntegrationTest extends BaseModuleContextSensitiveTest {

	@Test
	public void laterCorrectionReconciliationDoesNotReactivateExplicitlyVoidedHead() throws Exception {
		Encounter e = imported();
		Obs old = e.getObs().iterator().next();
		Obs first = Obs.newInstance(old);
		first.setPreviousVersion(old);
		first.setValueNumeric(64.0);
		ArrayNode values = m.createArrayNode();
		ObjectNode item = new EncounterCreationPayloadSerializer().observationVersion(first);
		item.put("rootUuid", old.getUuid());
		values.add(item);
		receive().receiveEncounter(
		    ObservationCorrectionEvent.create(e, values, Collections.singletonMap(old.getUuid(), first.getUuid()), origin,
		        2, base.plusSeconds(10)));
		ObjectNode voided = (ObjectNode) m.readTree(event(e, first));
		voided.put("entitySequence", 3);
		receive().receiveEncounter(voided.toString());
		Obs other = Obs.newInstance(old);
		other.setPreviousVersion(old);
		other.setValueNumeric(63.0);
		values = m.createArrayNode();
		item = new EncounterCreationPayloadSerializer().observationVersion(other);
		item.put("rootUuid", old.getUuid());
		values.add(item);
		receive().receiveEncounter(
		    ObservationCorrectionEvent.create(e, values, Collections.singletonMap(old.getUuid(), other.getUuid()), origin
		            + "other", 1, base.plusSeconds(5)));
		assertTrue(stored(first.getUuid()).getVoided());
		assertTrue(stored(other.getUuid()).getVoided());
		assertEquals(64, stored(first.getUuid()).getValueNumeric());
	}

	@Test
	public void localRollbackRestoresVoidAndSequence() {
		Encounter e = imported();
		Obs old = e.getObs().iterator().next();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		long before = sync().getHighestEncounterSequence("testServer_1");
		Context.getObsService().voidObs(Context.getObsService().getObsByUuid(old.getUuid()), "Prueba de rollback");
		assertEquals(before + 1, sync().getHighestEncounterSequence("testServer_1"));
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertFalse(stored(old.getUuid()).getVoided());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
	}

	@Test
	public void receivesWithTechnicalRole() {
		technicalRole(true);
	}

	@Test
	public void rejectsTechnicalRoleWithoutEditPrivilege() {
		technicalRole(false);
	}

	private void technicalRole(boolean edit){
        Encounter e=imported();Obs old=e.getObs().iterator().next();String json=event(e,old);
        Role role=new Role(origin);
        List<String> privileges=new ArrayList<>(Arrays.asList("Receive Synchronization Records","View Synchronization Records","Get Encounters","Get Observations","Add Encounters","Add Observations","Get Patients","Get People","Get Global Properties","Get Locations","Get Encounter Types","Get Forms","Get Providers","Get Encounter Roles","Get Concepts","Get Orders"));
        if(edit)privileges.add("Edit Observations");
        for(String name:privileges){Privilege p=Context.getUserService().getPrivilege(name);if(p==null)p=Context.getUserService().savePrivilege(new Privilege(name,"Prueba"));role.addPrivilege(p);}
        role=Context.getUserService().saveRole(role);User user=new User();user.setPerson(Context.getPersonService().getPerson(2));user.setUsername(origin);user.addRole(role);Context.getUserService().createUser(user,"Test-Voids123!");
        org.openmrs.api.context.Credentials admin=getCredentials();EncounterReceiveService receiver=receive();Context.logout();
        try{Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(origin,"Test-Voids123!"));
            if(edit)assertEquals(2,receiver.receiveEncounter(json));else assertThrows(APIAuthenticationException.class,()->receiver.receiveEncounter(json));
        }finally{Context.logout();Context.authenticate(admin);}
        assertEquals(edit?2:1,receive().getConfirmedEncounterSequence(origin));assertEquals(edit,stored(old.getUuid()).getVoided());
    }

	private final EncounterCreationAdvice encounterAdvice = new EncounterCreationAdvice();

	private final ObservationAdditionAdvice obsAdvice = new ObservationAdditionAdvice();

	private final ObjectMapper m = new ObjectMapper();

	private final String origin = "void_" + UUID.randomUUID().toString().replace("-", "");

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

	private Obs stored(String id) {
		Context.flushSession();
		Context.clearSession();
		return Context.getObsService().getObsByUuid(id);
	}

	private String event(Encounter e, Obs... obs) {
		ArrayNode items = m.createArrayNode();
		for (Obs o : obs) {
			ObjectNode item = items.addObject();
			item.put("uuid", o.getUuid());
			item.put("reason", "Correccion ficticia");
			item.put("dateVoided", base.plusSeconds(20).toString());
		}
		return ObservationVoidEvent.create(e, items, origin, 2, base.plusSeconds(20));
	}

	@Test
	public void capturesVoidAndIndependentReplacementWithoutInventingPreviousVersion() throws Exception {
		Encounter e = sample();
		Obs old = obs(e, 62);
		e.addObs(old);
		Context.getEncounterService().saveEncounter(e);
		long before = sync().getHighestEncounterSequence("testServer_1");
		Context.getObsService().voidObs(old, "Valor corregido");
		Obs replacement = obs(e, 64);
		Context.getObsService().saveObs(replacement, null);
		List<EncounterSyncEvent> events = sync().getEncounterEventsAfter("testServer_1", before, 100);
		assertEquals(2, events.size());
		assertEquals("VOID_OBS", m.readTree(events.get(0).getPayloadJson()).path("operation").asText());
		assertEquals("ADD_OBS", m.readTree(events.get(1).getPayloadJson()).path("operation").asText());
		assertEquals(62, stored(old.getUuid()).getValueNumeric());
		assertTrue(stored(old.getUuid()).getVoided());
		assertNull(stored(replacement.getUuid()).getPreviousVersion());
		assertEquals(0, sync().prepareVoidedObservations(e.getUuid()));
	}

	@Test public void receivesAndReplaysWithoutEchoOrLossOfValue()throws Exception{
        Encounter e=imported();Obs old=e.getObs().iterator().next();String json=event(e,old);
        long before=sync().getHighestEncounterSequence("testServer_1");
        assertEquals(2,receive().receiveEncounter(json));assertEquals(2,receive().receiveEncounter(json));
        assertTrue(stored(old.getUuid()).getVoided());assertEquals(62,stored(old.getUuid()).getValueNumeric());
        assertEquals(before,sync().getHighestEncounterSequence("testServer_1"));
        assertEquals(json,sync().getEncounterEventsAfter(origin,1,100).get(0).getPayloadJson());
        ObjectNode changed=(ObjectNode)m.readTree(json);((ObjectNode)changed.path("payload").path("observations").get(0)).put("reason","Otra razon");
        assertThrows(APIException.class,()->receive().receiveEncounter(changed.toString()));
    }

	@Test public void rejectsForeignObservationWithoutAdvancingReceipt(){
        Encounter e=imported();Encounter other=sample();Obs foreign=obs(other,80);other.addObs(foreign);Context.getEncounterService().saveEncounter(other);
        assertThrows(APIException.class,()->receive().receiveEncounter(event(e,foreign)));
        assertEquals(1,receive().getConfirmedEncounterSequence(origin));assertFalse(stored(foreign.getUuid()).getVoided());
    }

	@Test public void waitsForMissingObservation(){
        Encounter e=imported();Obs missing=obs(e,70);
        assertThrows(EncounterDependencyException.class,()->receive().receiveEncounter(event(e,missing)));
        assertEquals(1,receive().getConfirmedEncounterSequence(origin));
    }

	@Test
	public void rollbackRestoresObservationAndReceipt() {
		Encounter e = imported();
		Obs old = e.getObs().iterator().next();
		String json = event(e, old);
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		receive().receiveEncounter(json);
		assertTrue(stored(old.getUuid()).getVoided());
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertFalse(stored(old.getUuid()).getVoided());
		assertEquals(1, receive().getConfirmedEncounterSequence(origin));
		assertEquals(2, receive().receiveEncounter(json));
	}

	@Test
	public void preparesAlreadyVoidedPublishedObservationOnce() {
		Encounter e = imported();
		Obs old = e.getObs().iterator().next();
		Context.removeAdvice(ObsService.class, obsAdvice);
		try {
			Context.getObsService().voidObs(old, "Correccion anterior al incremento");
		}
		finally {
			Context.addAdvice(ObsService.class, obsAdvice);
		}
		assertEquals(1, sync().prepareVoidedObservations(e.getUuid()));
		assertEquals(0, sync().prepareVoidedObservations(e.getUuid()));
	}

	@Test
	public void capturesVoidedGroupMembers() {
		Encounter e = sample();
		Obs group = obs(e, 0);
		group.setValueNumeric(null);
		group.setConcept(Context.getConceptService().getConcept(23));
		Obs member = obs(e, 62);
		group.addGroupMember(member);
		e.addObs(group);
		Context.getEncounterService().saveEncounter(e);
		long before = sync().getHighestEncounterSequence("testServer_1");
		Context.getObsService().voidObs(group, "Grupo retirado");
		List<EncounterSyncEvent> events = sync().getEncounterEventsAfter("testServer_1", before, 100);
		assertEquals(1, events.size());
		assertEquals(2, new ObservationVoidEvent(events.get(0).getPayloadJson()).observations().size());
	}

	@Test public void strictContractRejectsDuplicatesAndUnexpectedFields()throws Exception{
        Encounter e=imported();Obs old=e.getObs().iterator().next();
        assertThrows(APIException.class,()->new ObservationVoidEvent(event(e,old,old)));
        ObjectNode changed=(ObjectNode)m.readTree(event(e,old));changed.put("schemaVersion",Long.MAX_VALUE);
        assertThrows(APIException.class,()->new ObservationVoidEvent(changed.toString()));
        changed.put("schemaVersion",9);((ObjectNode)changed.path("payload").path("observations").get(0)).put("value",90);
        assertThrows(APIException.class,()->new ObservationVoidEvent(changed.toString()));
    }
}

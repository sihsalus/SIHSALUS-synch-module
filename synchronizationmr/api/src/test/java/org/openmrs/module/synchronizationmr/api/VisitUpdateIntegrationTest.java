package org.openmrs.module.synchronizationmr.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.*;
import org.openmrs.api.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.advice.VisitChangeAdvice;
import org.openmrs.module.synchronizationmr.sync.*;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class VisitUpdateIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private long beforeSequence;
	
	private final VisitChangeAdvice advice = new VisitChangeAdvice();
	
	private final ObjectMapper m = new ObjectMapper();
	
	private final Instant base = Instant.parse("2026-01-01T00:00:00Z");
	
	private final String origin = "visit_" + UUID.randomUUID();
	
	@BeforeEach
	public void setup() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		Context.addAdvice(VisitService.class, advice);
		beforeSequence = sync().getHighestEncounterSequence("testServer_1");
	}
	
	@AfterEach
	public void cleanup() {
		Context.removeAdvice(VisitService.class, advice);
	}
	
	private EncounterSyncService sync() {
		return Context.getService(EncounterSyncService.class);
	}
	
	private EncounterReceiveService receiver() {
		return Context.getService(EncounterReceiveService.class);
	}
	
	private Encounter sample() {
		Encounter e = new Encounter();
		e.setPatient(Context.getPatientService().getPatient(2));
		e.setEncounterType(Context.getEncounterService().getEncounterType(1));
		e.setLocation(Context.getLocationService().getLocation(1));
		e.setEncounterDatetime(Date.from(base.plusSeconds(60)));
		Visit v = new Visit();
		v.setPatient(e.getPatient());
		v.setVisitType(Context.getVisitService().getVisitType(1));
		v.setLocation(e.getLocation());
		v.setStartDatetime(Date.from(base));
		e.setVisit(v);
		return e;
	}
	
	private String create(Encounter e, String node, long seq) {
		return new EncounterCreationPayloadSerializer().serialize(e, node, seq, UUID.randomUUID().toString(),
		    Date.from(base));
	}
	
	private Encounter imported() {
		Encounter e = sample();
		receiver().receiveEncounter(create(e, origin, 1));
		return Context.getEncounterService().getEncounterByUuid(e.getUuid());
	}
	
	private Visit fresh(Visit v) {
		Context.flushSession();
		Context.clearSession();
		return Context.getVisitService().getVisitByUuid(v.getUuid());
	}
	
	private String event(Encounter e, String node, long seq, int end, int time) {
		Visit v = new Visit();
		v.setUuid(e.getVisit().getUuid());
		v.setPatient(e.getPatient());
		v.setStartDatetime(Date.from(base));
		v.setStopDatetime(end < 0 ? null : Date.from(base.plusSeconds(end)));
		return VisitUpdateEvent.create(v, e.getUuid(), node, seq, base.plusSeconds(time));
	}
	
	@Test
	public void localClosureCapturedOnceAndKeepsCreation() {
		Encounter e = imported();
		String original = sync().getEncounterEventsAfter(origin, 0, 1).get(0).getPayloadJson();
		Visit v = e.getVisit();
		v.setStopDatetime(Date.from(base.plusSeconds(300)));
		Context.getVisitService().saveVisit(v);
		assertEquals(beforeSequence + 1, sync().getHighestEncounterSequence("testServer_1"));
		VisitUpdateEvent update = new VisitUpdateEvent(sync().getEncounterEventsAfter("testServer_1", beforeSequence, 1)
		        .get(0).getPayloadJson());
		assertEquals(v.getUuid(), update.visitUuid);
		assertEquals(e.getUuid(), update.encounterUuid);
		Context.getVisitService().saveVisit(v);
		assertEquals(beforeSequence + 1, sync().getHighestEncounterSequence("testServer_1"));
		assertEquals(original, sync().getEncounterEventsAfter(origin, 0, 1).get(0).getPayloadJson());
	}
	
	@Test
	public void endVisitIsCapturedWithoutDuplicateNestedSave() {
		Encounter e = imported();
		Context.getVisitService().endVisit(e.getVisit(), Date.from(base.plusSeconds(300)));
		assertEquals(beforeSequence + 1, sync().getHighestEncounterSequence("testServer_1"));
		assertNotNull(fresh(e.getVisit()).getStopDatetime());
	}
	
	@Test
	public void unpublishedVisitDoesNotConsumeSequence() {
		Visit v = sample().getVisit();
		Context.getVisitService().saveVisit(v);
		v.setStopDatetime(Date.from(base.plusSeconds(300)));
		Context.getVisitService().saveVisit(v);
		assertEquals(beforeSequence, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void receivesClosureAndReplayWithoutLocalEcho() {
		Encounter e = imported();
		String json = event(e, origin, 2, 300, 400);
		assertEquals(2, receiver().receiveEncounter(json));
		assertEquals(2, receiver().receiveEncounter(json));
		assertEquals(Date.from(base.plusSeconds(300)), fresh(e.getVisit()).getStopDatetime());
		assertFalse(Context.getEncounterService().getEncounterByUuid(e.getUuid()).getVoided());
		assertEquals(beforeSequence, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void newerClosureWinsWhenDeliveredFirst() {
		conflict(true);
	}
	
	@Test
	public void newerClosureWinsWhenDeliveredLast() {
		conflict(false);
	}
	
	private void conflict(boolean first) {
		Encounter e = imported();
		String old = event(e, "older", 1, 300, 400), recent = event(e, "newer", 1, 600, 700);
		receiver().receiveEncounter(first ? recent : old);
		receiver().receiveEncounter(first ? old : recent);
		assertEquals(Date.from(base.plusSeconds(600)), fresh(e.getVisit()).getStopDatetime());
		assertEquals(1, receiver().getConfirmedEncounterSequence("older"));
	}
	
	@Test
	public void reopeningUsesLaterVersionWithoutDeletingEncounters() {
		Encounter e = imported();
		receiver().receiveEncounter(event(e, origin, 2, 300, 400));
		receiver().receiveEncounter(event(e, origin, 3, -1, 500));
		assertNull(fresh(e.getVisit()).getStopDatetime());
		assertNotNull(Context.getEncounterService().getEncounterByUuid(e.getUuid()));
	}
	
	@Test
	public void changedStartAndStopTravelTogether() throws Exception {
		Encounter e = imported();
		ObjectNode json = (ObjectNode) m.readTree(event(e, origin, 2, 300, 400));
		((ObjectNode) json.path("payload")).put("startDatetime", base.minusSeconds(30).toString());
		receiver().receiveEncounter(json.toString());
		Visit v = fresh(e.getVisit());
		assertEquals(Date.from(base.minusSeconds(30)), v.getStartDatetime());
		assertEquals(Date.from(base.plusSeconds(300)), v.getStopDatetime());
	}
	
	@Test
	public void lateEncounterWithOldVisitDoesNotReopenIt() {
		Encounter e = imported(), late = sample();
		late.getVisit().setUuid(e.getVisit().getUuid());
		String json = create(late, origin, 3);
		receiver().receiveEncounter(event(e, origin, 2, 300, 400));
		receiver().receiveEncounter(json);
		assertNotNull(Context.getEncounterService().getEncounterByUuid(late.getUuid()));
		assertEquals(Date.from(base.plusSeconds(300)), fresh(e.getVisit()).getStopDatetime());
	}
	
	@Test public void rejectsLateEncounterOutsideClosedVisitWithoutReopeningIt() {
        Encounter e=imported(),late=sample();late.getVisit().setUuid(e.getVisit().getUuid());late.setEncounterDatetime(Date.from(base.plusSeconds(500)));
        receiver().receiveEncounter(event(e,origin,2,300,400));
        assertThrows(APIException.class,()->receiver().receiveEncounter(create(late,origin,3)));
        assertEquals(2,receiver().getConfirmedEncounterSequence(origin));
        assertEquals(Date.from(base.plusSeconds(300)),e.getVisit().getStopDatetime());
    }
	
	@Test public void visitWithoutVersionStillRejectsDifferentSnapshot(){Encounter e=imported(),late=sample();late.getVisit().setUuid(e.getVisit().getUuid());late.getVisit().setStopDatetime(Date.from(base.plusSeconds(300)));assertThrows(APIException.class,()->receiver().receiveEncounter(create(late,origin,2)));}
	
	@Test public void rejectsWrongPatientWithoutReceipt()throws Exception{Encounter e=imported();ObjectNode json=(ObjectNode)m.readTree(event(e,origin,2,300,400));((ObjectNode)json.path("payload")).put("patientUuid",Context.getPatientService().getPatient(6).getUuid());assertThrows(APIException.class,()->receiver().receiveEncounter(json.toString()));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));assertNull(e.getVisit().getStopDatetime());}
	
	@Test public void rejectsWrongVisitWithoutReceipt()throws Exception{Encounter e=imported();ObjectNode json=(ObjectNode)m.readTree(event(e,origin,2,300,400));((ObjectNode)json.path("payload")).put("visitUuid",UUID.randomUUID().toString());assertThrows(APIException.class,()->receiver().receiveEncounter(json.toString()));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));}
	
	@Test public void rejectsReversedInterval()throws Exception{Encounter e=imported();ObjectNode json=(ObjectNode)m.readTree(event(e,origin,2,300,400));((ObjectNode)json.path("payload")).put("stopDatetime",base.minusSeconds(1).toString());assertThrows(APIException.class,()->receiver().receiveEncounter(json.toString()));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));}
	
	@Test public void rejectsAlteredReplay()throws Exception{Encounter e=imported();String json=event(e,origin,2,300,400);receiver().receiveEncounter(json);assertThrows(APIException.class,()->receiver().receiveEncounter(event(e,origin,2,600,700)));assertEquals(2,receiver().getConfirmedEncounterSequence(origin));}
	
	@Test public void strictContractRejectsExtraFieldsAndOverflow()throws Exception{Encounter e=imported();ObjectNode json=(ObjectNode)m.readTree(event(e,origin,2,300,400));json.put("schemaVersion",4294967307L);assertThrows(APIException.class,()->new VisitUpdateEvent(json.toString()));json.put("schemaVersion",11);((ObjectNode)json.path("payload")).put("unexpected",true);assertThrows(APIException.class,()->new VisitUpdateEvent(json.toString()));}
	
	@Test
	public void rollbackUndoesLocalClosureAndSequence() {
		Encounter e = imported();
		String id = e.getVisit().getUuid();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		Visit v = Context.getVisitService().getVisitByUuid(id);
		v.setStopDatetime(Date.from(base.plusSeconds(300)));
		Context.getVisitService().saveVisit(v);
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		Context.clearSession();
		assertNull(Context.getVisitService().getVisitByUuid(id).getStopDatetime());
		assertEquals(beforeSequence, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void rollbackUndoesReceivedClosureAndReceipt() {
		Encounter e = imported();
		String json = event(e, origin, 2, 300, 400), id = e.getVisit().getUuid();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		receiver().receiveEncounter(json);
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		Context.clearSession();
		assertNull(Context.getVisitService().getVisitByUuid(id).getStopDatetime());
		assertEquals(1, receiver().getConfirmedEncounterSequence(origin));
	}
	
	@Test
	public void secondLocalClosureUsesPersistedState() {
		Encounter e = imported();
		Visit v = e.getVisit();
		v.setStopDatetime(Date.from(base.plusSeconds(300)));
		Context.getVisitService().saveVisit(v);
		v.setStopDatetime(Date.from(base.plusSeconds(600)));
		Context.getVisitService().saveVisit(v);
		assertEquals(beforeSequence + 2, sync().getHighestEncounterSequence("testServer_1"));
		VisitUpdateEvent last = new VisitUpdateEvent(sync().getEncounterEventsAfter("testServer_1", beforeSequence + 1, 1)
		        .get(0).getPayloadJson());
		assertEquals(base.plusSeconds(600).toString(), last.snapshot.path("stopDatetime").asText());
	}
	
	@Test public void lateCreateCannotChangeOtherVisitMetadata(){
        Encounter e=imported(),late=sample();late.getVisit().setUuid(e.getVisit().getUuid());late.getVisit().setLocation(Context.getLocationService().getLocation(2));
        receiver().receiveEncounter(event(e,origin,2,300,400));
        assertThrows(APIException.class,()->receiver().receiveEncounter(create(late,origin,3)));
        assertEquals(1,e.getVisit().getLocation().getId());assertEquals(2,receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test
	public void technicalUserWithEditVisitsIsAccepted() throws Exception {
		technical(true);
	}
	
	@Test
	public void technicalUserWithoutEditVisitsIsRejected() throws Exception {
		technical(false);
	}
	
	private void technical(boolean edit) throws Exception {
        Encounter e=imported();String json=event(e,origin,2,300,400);String username="visitrole"+UUID.randomUUID().toString().substring(0,8);
        Role role=new Role(username);List<String> names=new ArrayList<>(Arrays.asList("Receive Synchronization Records","View Synchronization Records","Add Encounters","Add Observations","Get Encounters","Get Patients","Get People","Get Visits","Get Visit Attribute Types","Get Global Properties"));
        if(edit)names.add("Edit Visits");
        for(String name:names){Privilege privilege=Context.getUserService().getPrivilege(name);if(privilege==null)privilege=Context.getUserService().savePrivilege(new Privilege(name,"Prueba"));role.addPrivilege(privilege);}
        Context.getUserService().saveRole(role);User user=new User();user.setPerson(Context.getPersonService().getPerson(2));user.setUsername(username);user.addRole(role);
        String password=UUID.randomUUID().toString()+"aA1!";Context.getUserService().createUser(user,password);
        org.openmrs.api.context.Credentials admin=getCredentials();EncounterReceiveService service=receiver();Context.logout();
        try {Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(username,password));
            if(edit)assertEquals(2,service.receiveEncounter(json));else assertThrows(APIAuthenticationException.class,()->service.receiveEncounter(json));
        } finally {Context.logout();Context.authenticate(admin);}
        assertEquals(edit?2:1,receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void receivedClosureCannotExcludeExistingEncounter(){
        Encounter e=imported();
        assertThrows(APIException.class,()->receiver().receiveEncounter(event(e,origin,2,30,400)));
        assertEquals(1,receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void rejectsUnpublishedAnchorOfKnownVisit() throws Exception {
        Encounter e=imported(),other=sample();other.setVisit(e.getVisit());Context.getEncounterService().saveEncounter(other);
        ObjectNode json=(ObjectNode)m.readTree(event(e,origin,2,300,400));((ObjectNode)json.path("payload")).put("encounterUuid",other.getUuid());
        assertThrows(APIException.class,()->receiver().receiveEncounter(json.toString()));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));
    }
}

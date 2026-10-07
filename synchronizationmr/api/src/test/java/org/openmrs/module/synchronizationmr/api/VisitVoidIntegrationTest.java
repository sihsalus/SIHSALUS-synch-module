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
import org.openmrs.Order;
import org.openmrs.api.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.advice.*;
import org.openmrs.module.synchronizationmr.sync.*;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class VisitVoidIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private final VisitChangeAdvice visits = new VisitChangeAdvice();
	
	private final EncounterCreationAdvice encounters = new EncounterCreationAdvice();
	
	private final ObservationAdditionAdvice observations = new ObservationAdditionAdvice();
	
	private final String origin = "visitvoid_" + UUID.randomUUID();
	
	private final Instant base = Instant.parse("2026-01-01T00:00:00Z");
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private long before;
	
	@BeforeEach
	public void setup() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		Context.addAdvice(VisitService.class, visits);
		Context.addAdvice(EncounterService.class, encounters);
		Context.addAdvice(ObsService.class, observations);
		before = sync().getHighestEncounterSequence("testServer_1");
	}
	
	@AfterEach
	public void cleanup() {
		Context.removeAdvice(VisitService.class, visits);
		Context.removeAdvice(EncounterService.class, encounters);
		Context.removeAdvice(ObsService.class, observations);
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
		Obs o = new Obs();
		o.setPerson(e.getPatient());
		o.setConcept(Context.getConceptService().getConcept(5089));
		o.setLocation(e.getLocation());
		o.setObsDatetime(e.getEncounterDatetime());
		o.setValueNumeric(62.0);
		e.addObs(o);
		return e;
	}
	
	private String create(Encounter e, long seq) {
		return new EncounterCreationPayloadSerializer().serialize(e, origin, seq, UUID.randomUUID().toString(),
		    Date.from(base));
	}
	
	private Encounter imported() {
		Encounter e = sample();
		receiver().receiveEncounter(create(e, 1));
		return Context.getEncounterService().getEncounterByUuid(e.getUuid());
	}
	
	private String event(Encounter e, String node, long seq) {
		Visit v = new Visit();
		v.setUuid(e.getVisit().getUuid());
		v.setPatient(e.getPatient());
		v.setVoidReason("Visita de prueba anulada");
		v.setDateVoided(Date.from(base.plusSeconds(300)));
		return VisitVoidEvent.create(v, e.getUuid(), node, seq, base.plusSeconds(300));
	}
	
	private Visit fresh(Visit v) {
		Context.flushSession();
		Context.clearSession();
		return Context.getVisitService().getVisitByUuid(v.getUuid());
	}
	
	@Test
	public void localCascadePublishesOnceAndPreservesValues() {
		Encounter e = imported();
		String obs = e.getObs().iterator().next().getUuid();
		Context.getVisitService().voidVisit(e.getVisit(), "Prueba local");
		Context.getVisitService().voidVisit(e.getVisit(), "Prueba local");
		List<EncounterSyncEvent> events = sync().getEncounterEventsAfter("testServer_1", before, 100);
		assertEquals(1, events.stream().filter(x -> x.getPayloadJson().contains("VOID_VISIT")).count());
		assertEquals(e.getVisit().getUuid(), new VisitVoidEvent(events.get(events.size()-1).getPayloadJson()).visitUuid);
		assertTrue(fresh(e.getVisit()).getVoided());
		assertTrue(Context.getEncounterService().getEncounterByUuid(e.getUuid()).getVoided());
		assertTrue(Context.getObsService().getObsByUuid(obs).getVoided());
		assertEquals(62.0, Context.getObsService().getObsByUuid(obs).getValueNumeric());
	}
	
	@Test
	public void receivesCascadeAndExactReplayWithoutEcho() {
		Encounter e = imported();
		String json = event(e, origin, 2);
		String obs = e.getObs().iterator().next().getUuid();
		assertEquals(2, receiver().receiveEncounter(json));
		assertEquals(2, receiver().receiveEncounter(json));
		Visit v = fresh(e.getVisit());
		assertTrue(v.getVoided());
		assertEquals(Date.from(base.plusSeconds(300)), v.getDateVoided());
		assertTrue(Context.getEncounterService().getEncounterByUuid(e.getUuid()).getVoided());
		assertTrue(Context.getObsService().getObsByUuid(obs).getVoided());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
		assertEquals(json, sync().getEncounterEventsAfter(origin, 1, 10).get(0).getPayloadJson());
	}
	
	@Test
	public void unpublishedVisitDoesNotPublish() {
		Visit v = sample().getVisit();
		Context.getVisitService().saveVisit(v);
		Context.getVisitService().voidVisit(v, "Solo local");
		assertTrue(fresh(v).getVoided());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void lateEncounterIsRetainedVoidedAndDoesNotBlockReceipt() {
		Encounter e = imported(), late = sample();
		late.getVisit().setUuid(e.getVisit().getUuid());
		String json = create(late, 3);
		receiver().receiveEncounter(event(e, origin, 2));
		assertEquals(3, receiver().receiveEncounter(json));
		assertTrue(fresh(e.getVisit()).getVoided());
		Encounter saved = Context.getEncounterService().getEncounterByUuid(late.getUuid());
		assertTrue(saved.getVoided());
		assertEquals(1, saved.getAllObs(true).size());
		assertTrue(saved.getAllObs(true).iterator().next().getVoided());
		assertEquals(62.0, saved.getAllObs(true).iterator().next().getValueNumeric());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void lateClosureDoesNotRestoreVisit() {
		Encounter e = imported();
		Visit v = e.getVisit();
		v.setStopDatetime(Date.from(base.plusSeconds(200)));
		String close = VisitUpdateEvent.create(v, e.getUuid(), origin, 3, base.plusSeconds(400));
		v.setStopDatetime(null);
		receiver().receiveEncounter(event(e, origin, 2));
		assertEquals(3, receiver().receiveEncounter(close));
		Visit saved = fresh(v);
		assertTrue(saved.getVoided());
		assertEquals(Date.from(base.plusSeconds(200)), saved.getStopDatetime());
	}
	
	@Test public void rejectsForeignPatientWithoutConfirmation() throws Exception {
        Encounter e=imported();ObjectNode json=(ObjectNode)mapper.readTree(event(e,origin,2));((ObjectNode)json.path("payload")).put("patientUuid",UUID.randomUUID().toString());
        assertThrows(APIException.class,()->receiver().receiveEncounter(json.toString()));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));assertFalse(fresh(e.getVisit()).getVoided());
    }
	
	@Test public void rejectsForeignVisitWithoutConfirmation() throws Exception {
        Encounter e=imported();Visit other=sample().getVisit();Context.getVisitService().saveVisit(other);
        ObjectNode json=(ObjectNode)mapper.readTree(event(e,origin,2));((ObjectNode)json.path("payload")).put("visitUuid",other.getUuid());
        assertThrows(APIException.class,()->receiver().receiveEncounter(json.toString()));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));assertFalse(fresh(other).getVoided());
    }
	
	@Test public void rejectsAlteredReplay() throws Exception {
        Encounter e=imported();String json=event(e,origin,2);receiver().receiveEncounter(json);
        ObjectNode altered=(ObjectNode)mapper.readTree(json);((ObjectNode)altered.path("payload")).put("reason","Distinta");
        assertThrows(APIException.class,()->receiver().receiveEncounter(altered.toString()));assertEquals(2,receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void rejectsUnsupportedFieldsAndInvalidReason() throws Exception {
        Encounter e=imported();ObjectNode json=(ObjectNode)mapper.readTree(event(e,origin,2));ObjectNode payload=(ObjectNode)json.path("payload");
        payload.put("purge",true);assertThrows(APIException.class,()->new VisitVoidEvent(json.toString()));payload.remove("purge");
        payload.put("reason"," ");assertThrows(APIException.class,()->new VisitVoidEvent(json.toString()));
    }
	
	@Test
	public void cascadeIncludesExistingOrder() {
		Encounter e = imported();
		Order o = new Order();
		o.setPatient(e.getPatient());
		o.setEncounter(e);
		o.setConcept(Context.getConceptService().getConcept(5089));
		o.setOrderType(Context.getOrderService().getOrderType(17));
		o.setOrderer(Context.getProviderService().getProvider(1));
		o.setCareSetting(Context.getOrderService().getCareSetting(1));
		o.setDateActivated(Date.from(base.plusSeconds(61)));
		o.setInstructions("Orden ficticia de visita");
		Context.getOrderService().saveRetrospectiveOrder(o, null);
		String uuid = o.getUuid();
		fresh(e.getVisit());
		e = Context.getEncounterService().getEncounterByUuid(e.getUuid());
		receiver().receiveEncounter(event(e, origin, 2));
		fresh(e.getVisit());
		assertTrue(Context.getOrderService().getOrderByUuid(uuid).getVoided());
		assertEquals("Orden ficticia de visita", Context.getOrderService().getOrderByUuid(uuid).getInstructions());
	}
	
	@Test
	public void rollbackRestoresLocalCascadeAndEvent() {
		Encounter e = imported();
		String id = e.getUuid(), visit = e.getVisit().getUuid(), obs = e.getObs().iterator().next().getUuid();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		Context.getVisitService().voidVisit(Context.getVisitService().getVisitByUuid(visit), "Revertir prueba");
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		Context.clearSession();
		assertFalse(Context.getVisitService().getVisitByUuid(visit).getVoided());
		assertFalse(Context.getEncounterService().getEncounterByUuid(id).getVoided());
		assertFalse(Context.getObsService().getObsByUuid(obs).getVoided());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void rollbackRestoresRemoteCascadeAndReceipt() {
		Encounter e = imported();
		String visit = e.getVisit().getUuid(), json = event(e, origin, 2);
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		receiver().receiveEncounter(json);
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		Context.clearSession();
		assertFalse(Context.getVisitService().getVisitByUuid(visit).getVoided());
		assertEquals(1, receiver().getConfirmedEncounterSequence(origin));
		assertTrue(sync().getEncounterEventsAfter(origin, 1, 10).isEmpty());
	}
	
	@Test
	public void technicalUserWithDeleteVisitsCanReceive() throws Exception {
		technical(true);
	}
	
	@Test
	public void technicalUserWithoutDeleteVisitsCannotReceive() throws Exception {
		technical(false);
	}
	
	private void technical(boolean allowed) throws Exception {
        Encounter e=imported();String json=event(e,origin,2),username="voidvisit"+UUID.randomUUID().toString().substring(0,8);
        Role role=new Role(username);
        List<String> names=new ArrayList<>(Arrays.asList("Receive Synchronization Records","View Synchronization Records","Add Encounters","Add Observations","Get Encounters","Get Patients","Get People","Get Visits","Get Visit Attribute Types","Get Global Properties","Get Locations","Get Encounter Types","Get Forms","Get Providers","Get Encounter Roles","Get Concepts","Get Observations","Get Orders","Delete Encounters","Delete Observations","Delete Orders","Edit Encounters","Edit Observations","Edit Visits"));
        if(allowed)names.add("Delete Visits");
        for(String name:names){Privilege p=Context.getUserService().getPrivilege(name);if(p==null)p=Context.getUserService().savePrivilege(new Privilege(name,"Prueba"));role.addPrivilege(p);}
        Context.getUserService().saveRole(role);User user=new User();user.setPerson(Context.getPersonService().getPerson(2));user.setUsername(username);user.addRole(role);
        String password=UUID.randomUUID().toString()+"aA1!";Context.getUserService().createUser(user,password);
        org.openmrs.api.context.Credentials admin=getCredentials();EncounterReceiveService service=receiver();Context.logout();
        try {Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(username,password));
            if(allowed)assertEquals(2,service.receiveEncounter(json));else assertThrows(APIAuthenticationException.class,()->service.receiveEncounter(json));
        } finally {Context.logout();Context.authenticate(admin);}
        assertEquals(allowed?2:1,receiver().getConfirmedEncounterSequence(origin));assertEquals(allowed,fresh(e.getVisit()).getVoided());
    }
	
	@Test public void lateEncounterWithDifferentLocationIsRejected(){
        Encounter e=imported(),late=sample();late.getVisit().setUuid(e.getVisit().getUuid());late.getVisit().setLocation(Context.getLocationService().getLocation(2));
        receiver().receiveEncounter(event(e,origin,2));assertThrows(APIException.class,()->receiver().receiveEncounter(create(late,3)));assertEquals(2,receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void independentUnpublishedAnnulmentDoesNotAuthorizeLateImport(){
        Encounter e=imported(),late=sample();late.getVisit().setUuid(e.getVisit().getUuid());String json=create(late,2);
        Context.removeAdvice(VisitService.class,visits);Context.getVisitService().voidVisit(e.getVisit(),"Cambio no publicado");Context.addAdvice(VisitService.class,visits);
        assertThrows(APIException.class,()->receiver().receiveEncounter(json));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test
	public void closureThenAnnulmentPreservesIntervalAndHistory() {
		Encounter e = imported();
		Visit v = e.getVisit();
		v.setStopDatetime(Date.from(base.plusSeconds(200)));
		String close = VisitUpdateEvent.create(v, e.getUuid(), origin, 2, base.plusSeconds(250));
		v.setStopDatetime(null);
		receiver().receiveEncounter(close);
		receiver().receiveEncounter(event(e, origin, 3));
		Visit saved = fresh(v);
		assertTrue(saved.getVoided());
		assertEquals(Date.from(base.plusSeconds(200)), saved.getStopDatetime());
		assertEquals(3, receiver().getConfirmedEncounterSequence(origin));
	}
	
	@Test public void missingAnchorDoesNotAdvanceReceipt() throws Exception {
        Encounter e=imported();ObjectNode json=(ObjectNode)mapper.readTree(event(e,origin,2));((ObjectNode)json.path("payload")).put("encounterUuid",UUID.randomUUID().toString());
        assertThrows(EncounterDependencyException.class,()->receiver().receiveEncounter(json.toString()));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));
    }
}

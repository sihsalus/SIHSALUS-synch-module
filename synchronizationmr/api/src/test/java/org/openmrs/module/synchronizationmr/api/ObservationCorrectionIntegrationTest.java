package org.openmrs.module.synchronizationmr.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
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

public class ObservationCorrectionIntegrationTest extends BaseModuleContextSensitiveTest {
	
	@Test
	public void importsCorrectedGroupAndItsMembersWithoutChangingHistoricalGrouping() {
		Encounter e = sample();
		Obs group = obs(e);
		group.setValueNumeric(null);
		group.setConcept(Context.getConceptService().getConcept(23));
		Obs member = obs(e);
		group.addGroupMember(member);
		e.addObs(group);
		create(e);
		Obs newGroup = Obs.newInstance(stored(group.getUuid()));
		newGroup.setPreviousVersion(group);
		newGroup.setComment("Grupo corregido");
		Obs newMember = newGroup.getGroupMembers().iterator().next();
		newMember.setValueNumeric(77.0);
		receive().receiveEncounter(correction(e, "a", 1, 10, newGroup, newMember));
		assertEquals(newGroup.getUuid(), stored(newMember.getUuid()).getObsGroup().getUuid());
		assertEquals(group.getUuid(), stored(member.getUuid()).getObsGroup().getUuid());
		assertTrue(stored(group.getUuid()).getVoided());
		assertFalse(stored(newGroup.getUuid()).getVoided());
		assertEquals(77.0, stored(newMember.getUuid()).getValueNumeric());
	}
	
	@Test
	public void capturesAndImportsNewMemberWithinCorrectedGroup() throws Exception {
		Encounter e = sample();
		Obs group = obs(e);
		group.setValueNumeric(null);
		group.setConcept(Context.getConceptService().getConcept(23));
		Obs member = obs(e);
		group.addGroupMember(member);
		e.addObs(group);
        Context.getEncounterService().saveEncounter(e);
        String groupId = group.getUuid();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
        group = Context.getObsService().getObsByUuid(groupId);
        e = group.getEncounter();
        group.setComment("CorrecciÃ³n de grupo");
		Obs added = obs(e);
		added.setValueNumeric(83.0);
		group.addGroupMember(added);
		Context.getObsService().saveObs(group, "CorrecciÃ³n con nuevo miembro");
		List<EncounterSyncEvent> events = sync().getEncounterEventsAfter("testServer_1", before, 100);
		assertEquals(2, events.size());
		ObservationCorrectionEvent event = new ObservationCorrectionEvent(events.get(1).getPayloadJson());
        assertEquals(3, event.observations().size());
        ObjectNode incoming = (ObjectNode) mapper.readTree(event.json);
        incoming.put("originServerId", prefix + "a");
        incoming.put("entitySequence", 1);
        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();
        Context.clearSession();
        assertEquals(1, receive().receiveEncounter(incoming.toString()));
        Obs receivedGroup = stored(event.heads.get(groupId));
        assertEquals(2, receivedGroup.getGroupMembers().size());
        assertTrue(receivedGroup.getGroupMembers().stream().anyMatch(o -> Double.valueOf(83).equals(o.getValueNumeric())));
        assertTrue(stored(groupId).getVoided());
	}
	
	@Test
	public void repeatedSaveAfterReceivingCorrectionDoesNotInventAnotherRevision() {
		Encounter e = sample();
		Obs original = obs(e);
		e.addObs(original);
		create(e);
		Obs corrected = revision(original, 72);
		receive().receiveEncounter(correction(e, "a", 1, 10, corrected));
		Obs current = stored(corrected.getUuid());
		Context.getObsService().saveObs(current, "Sin cambios");
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void rollbackOfReceptionRestoresOriginalReceiptAndHistory() {
		Encounter e = sample();
		Obs original = obs(e);
		e.addObs(original);
		create(e);
		Obs corrected = revision(original, 74);
		String json = correction(e, "a", 1, 10, corrected);
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		receive().receiveEncounter(json);
		assertTrue(Context.getObsService().getObsByUuid(original.getUuid()).getVoided());
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		Context.clearSession();
		assertFalse(Context.getObsService().getObsByUuid(original.getUuid()).getVoided());
		assertNull(Context.getObsService().getObsByUuid(corrected.getUuid()));
		assertEquals(0, receive().getConfirmedEncounterSequence(prefix + "a"));
		assertEquals(1, receive().receiveEncounter(json));
	}
	
	@Test
	public void preservesOrderLinkWhenOrderArrivesAfterCorrection() throws Exception {
		Encounter e = sample();
		Obs original = obs(e);
		Order order = new Order();
		order.setPatient(e.getPatient());
		order.setEncounter(e);
		order.setConcept(original.getConcept());
		order.setOrderType(Context.getOrderService().getOrderType(17));
		order.setOrderer(Context.getProviderService().getProvider(1));
		order.setCareSetting(Context.getOrderService().getCareSetting(1));
		order.setDateActivated(e.getEncounterDatetime());
		original.setOrder(order);
		e.addObs(original);
		create(e);
		Obs corrected = revision(original, 76);
		receive().receiveEncounter(correction(e, "a", 1, 10, corrected));
		assertNull(stored(corrected.getUuid()).getOrder());
		Context.getService(OrderReceiveService.class).receiveOrder(
		    new OrderCreationPayloadSerializer().serialize(order, prefix + "creator", 1, UUID.randomUUID().toString(),
		        java.util.Date.from(BASE)));
		assertEquals(order.getUuid(), stored(corrected.getUuid()).getOrder().getUuid());
		assertEquals(order.getUuid(), stored(original.getUuid()).getOrder().getUuid());
	}
	
	@Test public void rejectsMissingVersionCyclesAndAlteredReplay() throws Exception {
        Encounter e=sample();Obs original=obs(e);e.addObs(original);create(e);
        Obs corrected=revision(original,76);String json=correction(e,"a",1,10,corrected);
        ObjectNode invalid=(ObjectNode)mapper.readTree(json);
        ((ObjectNode)invalid.path("payload").path("obs").get(0)).put("previousVersionUuid",corrected.getUuid());
        assertThrows(APIException.class,()->receive().receiveEncounter(invalid.toString()));
        ((ObjectNode)invalid.path("payload").path("obs").get(0)).put("previousVersionUuid",UUID.randomUUID().toString());
        assertThrows(EncounterDependencyException.class,()->receive().receiveEncounter(invalid.toString()));
        assertEquals(0,receive().getConfirmedEncounterSequence(prefix+"a"));
        receive().receiveEncounter(json);
        ((ObjectNode)invalid.path("payload").path("obs").get(0)).put("previousVersionUuid",original.getUuid());
        ((ObjectNode)invalid.path("payload").path("obs").get(0)).put("valueNumeric",99);
        assertThrows(APIException.class,()->receive().receiveEncounter(invalid.toString()));
        assertEquals(76.0,stored(corrected.getUuid()).getValueNumeric());
    }
	
	@Test
	public void allowsTechnicalRoleWithEditObservations() throws Exception {
		technicalRole(true);
	}
	
	@Test
	public void rejectsTechnicalRoleWithoutEditObservations() throws Exception {
		technicalRole(false);
	}
	
	private void technicalRole(boolean edit) throws Exception {
        Encounter e=sample();Obs original=obs(e);e.addObs(original);create(e);Obs corrected=revision(original,74);
        String json=correction(e,"a",1,10,corrected);
        Role role=new Role(prefix);
        List<String> privileges=new ArrayList<>(Arrays.asList("Receive Synchronization Records","View Synchronization Records","Get Encounters","Get Observations","Add Encounters","Add Observations","Get Patients","Get People","Get Global Properties","Get Locations","Get Encounter Types","Get Forms","Get Providers","Get Encounter Roles","Get Concepts","Get Orders"));
        if(edit)privileges.add("Edit Observations");
        for(String name:privileges){Privilege p=Context.getUserService().getPrivilege(name);if(p==null)p=Context.getUserService().savePrivilege(new Privilege(name,"Prueba"));role.addPrivilege(p);}
        role=Context.getUserService().saveRole(role);User user=new User();user.setPerson(Context.getPersonService().getPerson(2));user.setUsername(prefix);user.addRole(role);Context.getUserService().createUser(user,"Test-Corrections123!");
        org.openmrs.api.context.Credentials admin=getCredentials();EncounterReceiveService receiver=receive();Context.logout();
        try{Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(prefix,"Test-Corrections123!"));
            if(edit)assertEquals(1,receiver.receiveEncounter(json));else assertThrows(APIAuthenticationException.class,()->receiver.receiveEncounter(json));
        }finally{Context.logout();Context.authenticate(admin);}
        assertEquals(edit?1:0,receive().getConfirmedEncounterSequence(prefix+"a"));
        assertEquals(edit,stored(original.getUuid()).getVoided());
    }
	
	private final EncounterCreationAdvice encounterAdvice = new EncounterCreationAdvice();
	
	private final ObservationAdditionAdvice obsAdvice = new ObservationAdditionAdvice();
	
	private final EncounterCreationPayloadSerializer serializer = new EncounterCreationPayloadSerializer();
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private final String prefix = "cor_" + UUID.randomUUID().toString().replace("-", "") + "_";
	
	private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
	
	private long before;
	
	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		Context.addAdvice(EncounterService.class, encounterAdvice);
		Context.addAdvice(ObsService.class, obsAdvice);
		before = sync().getHighestEncounterSequence("testServer_1");
	}
	
	@AfterEach
	public void cleanup() {
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
		e.setEncounterDatetime(java.util.Date.from(BASE));
		return e;
	}
	
	private Obs obs(Encounter e) {
		Obs o = new Obs();
		o.setPerson(e.getPatient());
		o.setEncounter(e);
		o.setConcept(Context.getConceptService().getConcept(5089));
		o.setObsDatetime(e.getEncounterDatetime());
		o.setLocation(e.getLocation());
		o.setValueNumeric(65.0);
		return o;
	}
	
	private Obs revision(Obs prior, double value) {
		Obs o = Obs.newInstance(prior);
		o.setPreviousVersion(prior);
		o.setVoided(false);
		o.setValueNumeric(value);
		return o;
	}
	
	private String create(Encounter e) {
		String json = serializer
		        .serialize(e, prefix + "creator", 1, UUID.randomUUID().toString(), java.util.Date.from(BASE));
		receive().receiveEncounter(json);
		return json;
	}
	
	private String correction(Encounter e,String origin,long seq,int seconds,Obs... versions){
        ArrayNode values=mapper.createArrayNode();Map<String,String> heads=new LinkedHashMap<>();
        for(Obs o:versions){Obs root=o;while(root.getPreviousVersion()!=null)root=root.getPreviousVersion();ObjectNode value=serializer.observationVersion(o);value.put("rootUuid",root.getUuid());values.add(value);heads.put(root.getUuid(),o.getUuid());}
        return ObservationCorrectionEvent.create(e,values,heads,prefix+origin,seq,BASE.plusSeconds(seconds));
    }
	
	private Obs stored(String uuid) {
		Context.flushSession();
		Context.clearSession();
		return Context.getObsService().getObsByUuid(uuid);
	}
	
	private long count(String sql)throws Exception{try(Statement q=getConnection().createStatement();ResultSet r=q.executeQuery(sql)){r.next();return r.getLong(1);}}
	
	@Test
	public void directSaveCapturesCorrectionOnceAndKeepsOriginalValue() throws Exception {
		Encounter e = sample();
		Obs old = obs(e);
		e.addObs(old);
		Context.getEncounterService().saveEncounter(e);
		String original = sync().getEncounterEventsAfter("testServer_1", before, 1).get(0).getPayloadJson();
		old.setValueNumeric(70.0);
		Obs corrected = Context.getObsService().saveObs(old, "CorrecciÃ³n ficticia");
		List<EncounterSyncEvent> events = sync().getEncounterEventsAfter("testServer_1", before, 100);
		assertEquals(2, events.size());
		assertEquals(original, events.get(0).getPayloadJson());
		ObservationCorrectionEvent event = new ObservationCorrectionEvent(events.get(1).getPayloadJson());
		assertEquals(corrected.getUuid(), event.heads.get(old.getUuid()));
		assertEquals(65.0, stored(old.getUuid()).getValueNumeric());
		assertTrue(stored(old.getUuid()).getVoided());
		assertEquals(70.0, stored(corrected.getUuid()).getValueNumeric());
		Obs current = Context.getObsService().getObsByUuid(corrected.getUuid());
		Context.getObsService().saveObs(current, "Sin cambios");
		assertEquals(before + 2, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void receivesSequentialCorrectionsWithoutEchoAndKeepsNativeChain() {
		Encounter e = sample();
		Obs first = obs(e);
		e.addObs(first);
		create(e);
		Obs second = revision(first, 70), third = revision(second, 75);
		String a = correction(e, "a", 1, 10, second), b = correction(e, "a", 2, 20, third);
		assertEquals(1, receive().receiveEncounter(a));
		assertEquals(1, receive().receiveEncounter(a));
		assertEquals(2, receive().receiveEncounter(b));
		assertEquals(second.getUuid(), stored(third.getUuid()).getPreviousVersion().getUuid());
		assertEquals(first.getUuid(), stored(second.getUuid()).getPreviousVersion().getUuid());
		assertTrue(stored(first.getUuid()).getVoided());
		assertTrue(stored(second.getUuid()).getVoided());
		assertFalse(stored(third.getUuid()).getVoided());
		assertEquals(75.0, stored(third.getUuid()).getValueNumeric());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void concurrentBranchesKeepBothValuesAndChooseNewestEvenWhenItArrivesFirst() throws Exception {
		Encounter e = sample();
		Obs first = obs(e);
		e.addObs(first);
		create(e);
		Obs a = revision(first, 70), b = revision(first, 80);
		receive().receiveEncounter(correction(e, "b", 1, 20, b));
		receive().receiveEncounter(correction(e, "a", 1, 10, a));
		assertFalse(stored(b.getUuid()).getVoided());
		assertTrue(stored(a.getUuid()).getVoided());
		assertEquals(70.0, stored(a.getUuid()).getValueNumeric());
		assertEquals(80.0, stored(b.getUuid()).getValueNumeric());
		assertEquals(first.getUuid(), stored(b.getUuid()).getPreviousVersion().getUuid());
		assertNull(stored(a.getUuid()).getPreviousVersion());
		assertEquals(1, count("select count(*) from synchronizationmr_obs_revision where obs_uuid='" + a.getUuid()
		        + "' and previous_uuid='" + first.getUuid() + "'"));
	}
	
	@Test
	public void newerBranchReplacesNativeSuccessorWithoutDeletingOlderBranch() {
		Encounter e = sample();
		Obs first = obs(e);
		e.addObs(first);
		create(e);
		Obs a = revision(first, 70), b = revision(first, 80);
		receive().receiveEncounter(correction(e, "a", 1, 10, a));
		receive().receiveEncounter(correction(e, "b", 1, 20, b));
		assertFalse(stored(b.getUuid()).getVoided());
		assertTrue(stored(a.getUuid()).getVoided());
		assertEquals(first.getUuid(), stored(b.getUuid()).getPreviousVersion().getUuid());
		Obs current = Context.getObsService().getObsByUuid(b.getUuid());
		current.setValueNumeric(90.0);
		Obs next = Context.getObsService().saveObs(current, "CorrecciÃ³n posterior");
		assertEquals(b.getUuid(), next.getPreviousVersion().getUuid());
		assertEquals(before + 1, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void correctsOnlyOneMemberOfExistingGroup() {
		Encounter e = sample();
		Obs group = obs(e);
		group.setValueNumeric(null);
		group.setConcept(Context.getConceptService().getConcept(23));
		Obs first = obs(e), other = obs(e);
		other.setValueNumeric(90.0);
		group.addGroupMember(first);
		group.addGroupMember(other);
		e.addObs(group);
		create(e);
		Obs change = revision(first, 72);
		receive().receiveEncounter(correction(e, "a", 1, 10, change));
		assertEquals(group.getUuid(), stored(change.getUuid()).getObsGroup().getUuid());
		assertFalse(stored(other.getUuid()).getVoided());
		assertEquals(90.0, stored(other.getUuid()).getValueNumeric());
		assertEquals(2, stored(group.getUuid()).getGroupMembers().size());
	}
	
	@Test
	public void encounterDateEditCapturesMetadataAndObservationVersions() throws Exception {
		Encounter e = sample();
		Obs old = obs(e);
		e.addObs(old);
		Context.getEncounterService().saveEncounter(e);
		e.setEncounterDatetime(java.util.Date.from(BASE.plusSeconds(60)));
		Context.getEncounterService().saveEncounter(e);
		List<EncounterSyncEvent> events = sync().getEncounterEventsAfter("testServer_1", before, 100);
		assertEquals(3, events.size());
		assertEquals("UPDATE", mapper.readTree(events.get(1).getPayloadJson()).path("operation").asText());
		ObservationCorrectionEvent correction = new ObservationCorrectionEvent(events.get(2).getPayloadJson());
		assertEquals(1, correction.heads.size());
		assertTrue(stored(old.getUuid()).getVoided());
	}
	
	@Test
	public void preservesTextCorrection() {
		Encounter e = sample();
		Obs first = obs(e);
		first.setConcept(Context.getConceptService().getConcept(19));
		first.setValueNumeric(null);
		first.setValueText("Nota ficticia inicial");
		e.addObs(first);
		create(e);
		Obs corrected = Obs.newInstance(first);
		corrected.setPreviousVersion(first);
		corrected.setValueText("Nota ficticia corregida");
		receive().receiveEncounter(correction(e, "a", 1, 10, corrected));
		assertEquals("Nota ficticia inicial", stored(first.getUuid()).getValueText());
		assertEquals("Nota ficticia corregida", stored(corrected.getUuid()).getValueText());
	}
	
	@Test public void rejectsForeignPreviousVersionAndDoesNotAdvanceReceipt(){
        Encounter first=sample();Obs old=obs(first);first.addObs(old);create(first);
        Encounter other=sample();other.setUuid(UUID.randomUUID().toString());
        receive().receiveEncounter(serializer.serialize(other,prefix+"other",1,UUID.randomUUID().toString(),java.util.Date.from(BASE)));
        Obs invalid=revision(old,70);invalid.setEncounter(other);
        assertThrows(APIException.class,()->receive().receiveEncounter(correction(other,"a",1,10,invalid)));
        assertEquals(0,receive().getConfirmedEncounterSequence(prefix+"a"));assertNull(Context.getObsService().getObsByUuid(invalid.getUuid()));
    }
	
	@Test
	public void localRollbackUndoesVersionEventAndCounter() throws Exception {
		Encounter e = sample();
		Obs old = obs(e);
		e.addObs(old);
		Context.getEncounterService().saveEncounter(e);
		String id = old.getUuid();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		old = Context.getObsService().getObsByUuid(id);
		old.setValueNumeric(75.0);
		Obs changed = Context.getObsService().saveObs(old, "Prueba rollback");
		String newId = changed.getUuid();
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		Context.clearSession();
		assertFalse(Context.getObsService().getObsByUuid(id).getVoided());
		assertNull(Context.getObsService().getObsByUuid(newId));
		assertEquals(before + 1, sync().getHighestEncounterSequence("testServer_1"));
	}
}

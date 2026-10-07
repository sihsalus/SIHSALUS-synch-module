package org.openmrs.module.synchronizationmr.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import java.sql.PreparedStatement;
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

public class VisitMetadataIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private final VisitChangeAdvice advice = new VisitChangeAdvice();
	
	private final ObjectMapper m = new ObjectMapper();
	
	private final Instant base = Instant.parse("2026-01-01T00:00:00Z");
	
	private final String origin = "meta_" + UUID.randomUUID();
	
	private VisitAttributeType textType, conceptType;
	
	private VisitType otherType;
	
	private long before;
	
	@BeforeEach
	public void setup() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		Context.addAdvice(VisitService.class, advice);
		textType = attributeType(VisitMetadata.TEXT);
		conceptType = attributeType(VisitMetadata.CONCEPT);
		otherType = Context.getVisitService().saveVisitType(
		    new VisitType("Otra visita " + UUID.randomUUID(), "Prueba ficticia"));
		before = sync().getHighestEncounterSequence("testServer_1");
	}
	
	@AfterEach
	public void cleanup() {
		Context.removeAdvice(VisitService.class, advice);
	}
	
	private VisitAttributeType attributeType(String datatype) {
		VisitAttributeType t = new VisitAttributeType();
		t.setName("Prueba " + UUID.randomUUID());
		t.setMinOccurs(0);
		t.setMaxOccurs(1);
		t.setDatatypeClassname(datatype);
		return Context.getVisitService().saveVisitAttributeType(t);
	}
	
	private EncounterReceiveService receiver() {
		return Context.getService(EncounterReceiveService.class);
	}
	
	private EncounterSyncService sync() {
		return Context.getService(EncounterSyncService.class);
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
	
	private String create(Encounter e, long seq) {
		return new EncounterCreationPayloadSerializer().serialize(e, origin, seq, UUID.randomUUID().toString(),
		    Date.from(base));
	}
	
	private Encounter imported() {
		Encounter e = sample();
		receiver().receiveEncounter(create(e, 1));
		return Context.getEncounterService().getEncounterByUuid(e.getUuid());
	}
	
	private String full(Encounter e, long seq, int time) {
		return VisitUpdateEvent.createFull(e.getVisit(), e.getUuid(), origin, seq, base.plusSeconds(time));
	}
	
	private VisitAttribute attr(Visit v, VisitAttributeType type, String value) {
		VisitAttribute a = new VisitAttribute();
		a.setAttributeType(type);
		a.setValueReferenceInternal(value);
		v.addAttribute(a);
		return a;
	}
	
	private ObjectNode event(Encounter e, long seq, int time) throws Exception {
		return (ObjectNode) m.readTree(full(e, seq, time));
	}
	
	private ObjectNode metadata(ObjectNode event) {
		return (ObjectNode) event.path("payload").path("metadata");
	}
	
	private Visit fresh(Encounter e) {
		String id = e.getVisit().getUuid();
		Context.flushSession();
		Context.clearSession();
		return Context.getVisitService().getVisitByUuid(id);
	}
	
	@Test
	public void capturesMetadataWithoutDateChangeAndDoesNotRepeatOnSave() {
		Encounter e = imported();
		Visit v = e.getVisit();
		v.setLocation(Context.getLocationService().getLocation(2));
		v.setVisitType(otherType);
		attr(v, textType, "Cash");
		Context.getVisitService().saveVisit(v);
		assertEquals(before + 1, sync().getHighestEncounterSequence("testServer_1"));
		VisitUpdateEvent change = new VisitUpdateEvent(sync().getEncounterEventsAfter("testServer_1", before, 1).get(0)
		        .getPayloadJson());
		assertTrue(change.full);
		assertEquals(otherType.getUuid(), change.snapshot.path("metadata").path("visitTypeUuid").asText());
		Context.getVisitService().saveVisit(v);
		assertEquals(before + 1, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void unchangedPublishedVisitDoesNotEmitUpdate() {
		Encounter e = imported();
		Context.getVisitService().saveVisit(e.getVisit());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void receivesTypeLocationAndAttributesWithoutEchoAndReplaysExactly() throws Exception {
		Encounter e = imported();
		ObjectNode json = event(e, 2, 400);
		ObjectNode data = metadata(json);
		data.put("visitTypeUuid", otherType.getUuid());
		data.put("locationUuid", Context.getLocationService().getLocation(2).getUuid());
		Visit template = sample().getVisit();
		attr(template, textType, "Cash");
		data.set("attributes", VisitMetadata.snapshot(template).get("attributes"));
		receiver().receiveEncounter(json.toString());
		receiver().receiveEncounter(json.toString());
		Visit v = fresh(e);
		assertEquals(otherType.getUuid(), v.getVisitType().getUuid());
		assertEquals(2, v.getLocation().getId());
		assertEquals("Cash", v.getActiveAttributes().iterator().next().getValueReference());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void supportsConceptAttributeInInitialCreate() throws Exception {
		Encounter e = sample();
		attr(e.getVisit(), conceptType, Context.getConceptService().getConcept(3).getUuid());
		String json = create(e, 1);
		assertEquals(14, m.readTree(json).path("schemaVersion").asInt());
		receiver().receiveEncounter(json);
		Visit v = Context.getVisitService().getVisitByUuid(e.getVisit().getUuid());
		assertEquals(1, v.getActiveAttributes().size());
	}
	
	@Test
	public void supportsConceptAttributeUpdate() throws Exception {
		Encounter e = imported();
		ObjectNode json = event(e, 2, 400);
		Visit template = sample().getVisit();
		String value = Context.getConceptService().getConcept(3).getUuid();
		attr(template, conceptType, value);
		metadata(json).set("attributes", VisitMetadata.snapshot(template).get("attributes"));
		receiver().receiveEncounter(json.toString());
		assertEquals(value, fresh(e).getActiveAttributes().iterator().next().getValueReference());
	}
	
	@Test
	public void replacingAndClearingAttributesPreservesVoidedHistory() throws Exception {
		Encounter source = sample();
		VisitAttribute old = attr(source.getVisit(), textType, "Old");
		receiver().receiveEncounter(create(source, 1));
		Encounter e = Context.getEncounterService().getEncounterByUuid(source.getUuid());
		ObjectNode update = event(e, 2, 400);
		Visit template = sample().getVisit();
		VisitAttribute replacement = attr(template, textType, "New");
		metadata(update).set("attributes", VisitMetadata.snapshot(template).get("attributes"));
		receiver().receiveEncounter(update.toString());
		Visit v = fresh(e);
		assertEquals(2, v.getAttributes().size());
		assertEquals(1, v.getActiveAttributes().size());
		assertTrue(Context.getVisitService().getVisitAttributeByUuid(old.getUuid()).getVoided());
		ObjectNode clear = event(Context.getEncounterService().getEncounterByUuid(e.getUuid()), 3, 500);
		metadata(clear).putArray("attributes");
		receiver().receiveEncounter(clear.toString());
		assertTrue(fresh(e).getActiveAttributes().isEmpty());
		assertTrue(Context.getVisitService().getVisitAttributeByUuid(replacement.getUuid()).getVoided());
	}
	
	@Test
	public void latestMetadataWinsRegardlessOfDeliveryOrder() throws Exception {
		conflict(true);
	}
	
	@Test
	public void latestMetadataWinsWhenDeliveredLast() throws Exception {
		conflict(false);
	}
	
	private void conflict(boolean reverse) throws Exception {
		Encounter e = imported();
		ObjectNode older = event(e, 1, 400), newer = event(e, 1, 500);
		older.put("originServerId", "older");
		newer.put("originServerId", "newer");
		metadata(older).put("locationUuid", Context.getLocationService().getLocation(2).getUuid());
		metadata(newer).put("visitTypeUuid", otherType.getUuid());
		receiver().receiveEncounter((reverse ? newer : older).toString());
		receiver().receiveEncounter((reverse ? older : newer).toString());
		Visit v = fresh(e);
		assertEquals(otherType.getUuid(), v.getVisitType().getUuid());
		assertEquals(1, v.getLocation().getId());
		assertEquals(1, receiver().getConfirmedEncounterSequence("older"));
	}
	
	@Test
	public void lateCreateDoesNotRevertVersionedMetadata() throws Exception {
		Encounter e = imported(), late = sample();
		late.getVisit().setUuid(e.getVisit().getUuid());
		String original = create(late, 3);
		ObjectNode update = event(e, 2, 400);
		metadata(update).put("visitTypeUuid", otherType.getUuid());
		receiver().receiveEncounter(update.toString());
		receiver().receiveEncounter(original);
		assertEquals(otherType.getUuid(), fresh(e).getVisitType().getUuid());
		assertNotNull(Context.getEncounterService().getEncounterByUuid(late.getUuid()));
	}
	
	@Test
	public void legacyIntervalUpdatePreservesNewMetadata() throws Exception {
		Encounter e = imported();
		ObjectNode update = event(e, 2, 400);
		metadata(update).put("visitTypeUuid", otherType.getUuid());
		receiver().receiveEncounter(update.toString());
		Visit template = sample().getVisit();
		template.setUuid(e.getVisit().getUuid());
		template.setStopDatetime(Date.from(base.plusSeconds(300)));
		receiver().receiveEncounter(VisitUpdateEvent.create(template, e.getUuid(), origin, 3, base.plusSeconds(500)));
		Visit v = fresh(e);
		assertEquals(otherType.getUuid(), v.getVisitType().getUuid());
		assertNotNull(v.getStopDatetime());
	}
	
	@Test public void upgradesStoredIntervalStateWithoutPhantomUpdate() throws Exception {
        Encounter e=imported();receiver().receiveEncounter(VisitUpdateEvent.create(e.getVisit(),e.getUuid(),origin,2,base.plusSeconds(400)));
        try(PreparedStatement q=getConnection().prepareStatement("update synchronizationmr_visit_state set snapshot_json=? where visit_id=?")) {
            q.setString(1,VisitUpdateEvent.interval(e.getVisit()).toString());q.setInt(2,e.getVisit().getId());q.executeUpdate();
        }
        Context.getVisitService().saveVisit(e.getVisit());assertEquals(before,sync().getHighestEncounterSequence("testServer_1"));
        e.getVisit().setVisitType(otherType);Context.getVisitService().saveVisit(e.getVisit());assertEquals(before+1,sync().getHighestEncounterSequence("testServer_1"));
    }
	
	@Test public void unknownCatalogRejectsWithoutReceipt() throws Exception {
        Encounter e=imported();ObjectNode update=event(e,2,400);metadata(update).put("visitTypeUuid",UUID.randomUUID().toString());
        assertThrows(APIException.class,()->receiver().receiveEncounter(update.toString()));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void rejectsAttributeBelongingToAnotherVisit() throws Exception {
        Encounter first=sample();VisitAttribute a=attr(first.getVisit(),textType,"Private");receiver().receiveEncounter(create(first,1));
        Encounter other=sample();receiver().receiveEncounter(create(other,2));Encounter e=Context.getEncounterService().getEncounterByUuid(other.getUuid());
        ObjectNode update=event(e,3,400);metadata(update).set("attributes",VisitMetadata.snapshot(first.getVisit()).get("attributes"));
        assertThrows(APIException.class,()->receiver().receiveEncounter(update.toString()));assertEquals(2,receiver().getConfirmedEncounterSequence(origin));
        assertEquals(first.getVisit().getUuid(),Context.getVisitService().getVisitAttributeByUuid(a.getUuid()).getOwner().getUuid());
    }
	
	@Test public void rejectsUnknownConceptValueWithoutReceipt() throws Exception {
        Encounter e=imported();ObjectNode update=event(e,2,400);Visit template=sample().getVisit();attr(template,conceptType,UUID.randomUUID().toString());
        metadata(update).set("attributes",VisitMetadata.snapshot(template).get("attributes"));
        assertThrows(APIException.class,()->receiver().receiveEncounter(update.toString()));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void strictMetadataContractRejectsDuplicatesAndMissingFields() throws Exception {
        Encounter e=imported();ObjectNode update=event(e,2,400);Visit template=sample().getVisit();attr(template,textType,"Cash");
        ArrayNode attrs=(ArrayNode)VisitMetadata.snapshot(template).get("attributes");attrs.add(attrs.get(0).deepCopy());metadata(update).set("attributes",attrs);
        assertThrows(APIException.class,()->new VisitUpdateEvent(update.toString()));
        ObjectNode missing=event(e,2,400);metadata(missing).remove("attributes");assertThrows(APIException.class,()->new VisitUpdateEvent(missing.toString()));
    }
	
	@Test
	public void lateUpdateNeverRestoresVoidedVisitOrAttributes() throws Exception {
		Encounter e = imported();
		ObjectNode update = event(e, 3, 500);
		Visit template = sample().getVisit();
		attr(template, textType, "Cash");
		metadata(update).set("attributes", VisitMetadata.snapshot(template).get("attributes"));
		Visit annulled = sample().getVisit();
		annulled.setUuid(e.getVisit().getUuid());
		annulled.setVoidReason("Prueba");
		annulled.setDateVoided(Date.from(base.plusSeconds(400)));
		receiver().receiveEncounter(VisitVoidEvent.create(annulled, e.getUuid(), origin, 2, base.plusSeconds(400)));
		receiver().receiveEncounter(update.toString());
		Visit v = fresh(e);
		assertTrue(v.getVoided());
		assertEquals(1, v.getAttributes().size());
		assertTrue(v.getActiveAttributes().isEmpty());
	}
	
	@Test
	public void rollbackRevertsMetadataReceiptAndState() throws Exception {
		Encounter e = imported();
		String id = e.getVisit().getUuid();
		ObjectNode update = event(e, 2, 400);
		metadata(update).put("visitTypeUuid", otherType.getUuid());
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		receiver().receiveEncounter(update.toString());
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		Context.clearSession();
		assertEquals(1, Context.getVisitService().getVisitByUuid(id).getVisitType().getId());
		assertEquals(1, receiver().getConfirmedEncounterSequence(origin));
	}
	
	@Test public void incompatibleAttributeConfigurationRejectsWithoutReceipt() throws Exception {
        Encounter e=imported();Visit template=sample().getVisit();attr(template,textType,"Cash");ObjectNode update=event(e,2,400);
        metadata(update).set("attributes",VisitMetadata.snapshot(template).get("attributes"));
        ((ObjectNode)metadata(update).path("attributes").get(0)).put("datatypeConfig","otra configuración");
        assertThrows(APIException.class,()->receiver().receiveEncounter(update.toString()));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test public void attributeCardinalityIsValidated() throws Exception {
        Encounter e=imported();Visit template=sample().getVisit();attr(template,textType,"Cash");attr(template,textType,"Other");ObjectNode update=event(e,2,400);
        metadata(update).set("attributes",VisitMetadata.snapshot(template).get("attributes"));
        assertThrows(APIException.class,()->receiver().receiveEncounter(update.toString()));assertEquals(1,receiver().getConfirmedEncounterSequence(origin));
    }
	
	@Test
	public void simultaneousVersionsUseDeterministicOriginTieBreak() throws Exception {
		Encounter e = imported();
		ObjectNode a = event(e, 1, 400), b = event(e, 1, 400);
		a.put("originServerId", "a");
		b.put("originServerId", "b");
		metadata(b).put("visitTypeUuid", otherType.getUuid());
		receiver().receiveEncounter(b.toString());
		receiver().receiveEncounter(a.toString());
		assertEquals(otherType.getUuid(), fresh(e).getVisitType().getUuid());
	}
	
	@Test
	public void localMetadataRollbackRevertsSequenceAndVisit() throws Exception {
		Encounter e = imported();
		String id = e.getVisit().getUuid(), typeId = otherType.getUuid();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		Visit v = Context.getVisitService().getVisitByUuid(id);
		v.setVisitType(Context.getVisitService().getVisitTypeByUuid(typeId));
		Context.getVisitService().saveVisit(v);
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		Context.clearSession();
		assertEquals(1, Context.getVisitService().getVisitByUuid(id).getVisitType().getId());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
	}
	
	@Test
	public void technicalRoleCanReceiveMetadataWithoutAdministrator() throws Exception {
		Encounter e = imported();
		ObjectNode update = event(e, 2, 400);
		metadata(update).put("visitTypeUuid", otherType.getUuid());
		Visit template = sample().getVisit();
		attr(template, conceptType, Context.getConceptService().getConcept(3).getUuid());
		metadata(update).set("attributes", VisitMetadata.snapshot(template).get("attributes"));
		String username = "vm" + UUID.randomUUID().toString().substring(0, 8);
		Role role = new Role(username);
		for (String name : Arrays.asList("Receive Synchronization Records", "View Synchronization Records",
		    "Add Encounters", "Add Observations", "Get Encounters", "Get Patients", "Get People", "Get Visits",
		    "Get Visit Types", "Get Visit Attribute Types", "Get Global Properties", "Get Locations", "Get Concepts",
		    "Get Concept Attribute Types", "Edit Visits")) {
			Privilege privilege = Context.getUserService().getPrivilege(name);
			if (privilege == null)
				privilege = Context.getUserService().savePrivilege(new Privilege(name, "Prueba"));
			role.addPrivilege(privilege);
		}
		Context.getUserService().saveRole(role);
		User user = new User();
		user.setPerson(Context.getPersonService().getPerson(2));
		user.setUsername(username);
		user.addRole(role);
		String password = UUID.randomUUID().toString() + "aA1!";
		Context.getUserService().createUser(user, password);
		org.openmrs.api.context.Credentials admin = getCredentials();
		EncounterReceiveService service = receiver();
		Context.logout();
		try {
			Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(username, password));
			assertEquals(2, service.receiveEncounter(update.toString()));
		}
		finally {
			Context.logout();
			Context.authenticate(admin);
		}
		assertEquals(otherType.getUuid(), fresh(e).getVisitType().getUuid());
	}
	
}

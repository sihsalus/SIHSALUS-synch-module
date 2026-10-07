package org.openmrs.module.synchronizationmr.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import static org.junit.jupiter.api.Assertions.*;

public class VisitAttributesIntegrationTest extends BaseModuleContextSensitiveTest {
	
	@Test
	public void acceptsNativeDatePrecisionWhenPreparingLegacyVisit() throws Exception {
		Encounter e = sample();
		e.getVisit().setStartDatetime(new java.util.Date(e.getVisit().getStartDatetime().getTime() + 123));
		String old = legacy(json(e));
		e.getVisit().setStartDatetime(new java.util.Date(e.getVisit().getStartDatetime().getTime() / 1000 * 1000));
		assertEquals(1, receiver().receiveEncounter(EncounterVisitSupplement.prepare(old, e.getVisit())));
		assertEquals(old,
		    new EncounterIncomingEvent(sync().getEncounterEventsAfter(origin, 0, 1).get(0).getPayloadJson()).json);
	}
	
	@Test
	public void rollsBackVisitAttributesAndSupplementWhenReceptionIsRolledBack() throws Exception {
		Encounter e = sample();
		String wire = EncounterVisitSupplement.prepare(legacy(json(e)), e.getVisit());
		org.springframework.test.context.transaction.TestTransaction.flagForCommit();
		org.springframework.test.context.transaction.TestTransaction.end();
		org.springframework.test.context.transaction.TestTransaction.start();
		receiver().receiveEncounter(wire);
		org.springframework.test.context.transaction.TestTransaction.flagForRollback();
		org.springframework.test.context.transaction.TestTransaction.end();
		org.springframework.test.context.transaction.TestTransaction.start();
		Context.clearSession();
		assertNull(Context.getVisitService().getVisitByUuid(e.getVisit().getUuid()));
		assertNull(Context.getVisitService().getVisitAttributeByUuid(
		    e.getVisit().getAttributes().iterator().next().getUuid()));
		assertEquals(0, receiver().getConfirmedEncounterSequence(origin));
		assertTrue(sync().getEncounterEventsAfter(origin, 0, 1).isEmpty());
		assertEquals(1, receiver().receiveEncounter(wire));
	}
	
	@Test public void rejectsOversizedSchemaAndUnexpectedAttributeFields() throws Exception {
        Encounter e=sample();String old=legacy(json(e));
        ObjectNode wire=(ObjectNode)mapper.readTree(EncounterVisitSupplement.prepare(old,e.getVisit()));
        wire.put("schemaVersion",4294967304L);
        assertThrows(APIException.class,()->receiver().receiveEncounter(wire.toString()));
        wire.put("schemaVersion",8);
        ((ObjectNode)wire.path("visitSupplement").path("attributes").get(0)).put("extra","unexpected");
        assertThrows(APIException.class,()->receiver().receiveEncounter(wire.toString()));
        assertEquals(0,receiver().getConfirmedEncounterSequence(origin));
    }
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private final String origin = "visit_" + UUID.randomUUID();
	
	private VisitAttributeType type;
	
	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		type = new VisitAttributeType();
		type.setName("Tipo ficticio " + UUID.randomUUID());
		type.setMinOccurs(0);
		type.setMaxOccurs(1);
		type.setDatatypeClassname("org.openmrs.customdatatype.datatype.FreeTextDatatype");
		Context.getVisitService().saveVisitAttributeType(type);
	}
	
	private Encounter sample() {
		Encounter e = new Encounter();
		e.setPatient(Context.getPatientService().getPatient(2));
		e.setEncounterType(Context.getEncounterService().getEncounterType(1));
		e.setLocation(Context.getLocationService().getLocation(1));
		e.setEncounterDatetime(new java.util.Date(System.currentTimeMillis() / 1000 * 1000));
		Visit v = new Visit();
		v.setPatient(e.getPatient());
		v.setVisitType(Context.getVisitService().getVisitType(1));
		v.setLocation(e.getLocation());
		v.setStartDatetime(new java.util.Date(e.getEncounterDatetime().getTime() - 60000));
		e.setVisit(v);
		VisitAttribute a = new VisitAttribute();
		a.setAttributeType(type);
		a.setValueReferenceInternal("Referencia ficticia");
		v.addAttribute(a);
		return e;
	}
	
	private EncounterReceiveService receiver() {
		return Context.getService(EncounterReceiveService.class);
	}
	
	private EncounterSyncService sync() {
		return Context.getService(EncounterSyncService.class);
	}
	
	private String json(Encounter e) {
		return new EncounterCreationPayloadSerializer().serialize(e, origin, 1, UUID.randomUUID().toString(),
		    new java.util.Date());
	}
	
	private String legacy(String json) throws Exception {
		ObjectNode old = (ObjectNode) mapper.readTree(json);
		old.put("schemaVersion", 3);
		ObjectNode visit = (ObjectNode) old.path("payload").path("visit");
		visit.remove("attributes");
		visit.put("unsupportedAttributes", true);
		return old.toString();
	}
	
	private Visit saved(Encounter e) {
		Context.flushSession();
		Context.clearSession();
		return Context.getVisitService().getVisitByUuid(e.getVisit().getUuid());
	}
	
	@Test
	public void receivesTextAttributesAndReusesVisitWithoutDuplicates() throws Exception {
		Encounter e = sample();
		String json = json(e);
		assertEquals(7, mapper.readTree(json).path("schemaVersion").asInt());
		assertEquals(1, receiver().receiveEncounter(json));
		assertEquals(1, receiver().receiveEncounter(json));
		Visit v = saved(e);
		assertEquals(1, v.getAttributes().size());
		assertEquals(e.getVisit().getAttributes().iterator().next().getUuid(), v.getAttributes().iterator().next().getUuid());
		assertEquals("Referencia ficticia", v.getAttributes().iterator().next().getValueReference());
		Encounter next = sample();
		next.setVisit(e.getVisit());
		String second = new EncounterCreationPayloadSerializer().serialize(next, origin, 2, UUID.randomUUID().toString(),
		    new java.util.Date());
		assertEquals(2, receiver().receiveEncounter(second));
		assertEquals(1, saved(e).getAttributes().size());
	}
	
	@Test public void supplementsOldCreateWithoutChangingItsOriginalJsonAndForwardsSameWire()throws Exception {
        Encounter e=sample();String old=legacy(json(e));String wire=EncounterVisitSupplement.prepare(old,e.getVisit());
        assertThrows(APIException.class,()->receiver().receiveEncounter(old));assertEquals(0,receiver().getConfirmedEncounterSequence(origin));
        assertEquals(1,receiver().receiveEncounter(wire));assertEquals(1,receiver().receiveEncounter(wire));
        assertEquals(old,new EncounterIncomingEvent(wire).json);
        try(PreparedStatement q=getConnection().prepareStatement("select payload_json from synchronizationmr_encounter_event where event_uuid=?")) {
            q.setString(1,new EncounterIncomingEvent(old).eventUuid);try(ResultSet r=q.executeQuery()){assertTrue(r.next());assertEquals(old,r.getString(1));}
        }
        assertEquals(wire,sync().getEncounterEventsAfter(origin,0,1).get(0).getPayloadJson());
        assertEquals(1,saved(e).getAttributes().size());
        ObjectNode altered=(ObjectNode)mapper.readTree(wire);
        ((ObjectNode)altered.path("visitSupplement").path("attributes").get(0)).put("value","Alterado");
        assertThrows(APIException.class,()->receiver().receiveEncounter(altered.toString()));
        assertThrows(APIException.class,()->receiver().receiveEncounter(old));
    }
	
	@Test public void rejectsAlteredIdentityMetadataAndNonLegacySupplements()throws Exception {
        Encounter e=sample();String old=legacy(json(e));String wire=EncounterVisitSupplement.prepare(old,e.getVisit());
        ObjectNode changed=(ObjectNode)mapper.readTree(wire);changed.put("originServerId","otro");
        assertThrows(APIException.class,()->receiver().receiveEncounter(changed.toString()));
        ObjectNode changedVisit=(ObjectNode)mapper.readTree(wire);
        ((ObjectNode)changedVisit.path("visitSupplement")).put("patientUuid",UUID.randomUUID().toString());
        assertThrows(APIException.class,()->receiver().receiveEncounter(changedVisit.toString()));
        assertThrows(APIException.class,()->EncounterVisitSupplement.prepare(json(e),e.getVisit()));
        assertEquals(0,receiver().getConfirmedEncounterSequence(origin));assertNull(saved(e));
    }
	
	@Test public void rejectsUnknownDatatypeConfigurationAndDuplicateAttributeUuid()throws Exception {
        Encounter e=sample();ObjectNode original=(ObjectNode)mapper.readTree(json(e));
        for(String field:Arrays.asList("datatype","datatypeConfig","typeUuid")) {
            ObjectNode bad=original.deepCopy();((ObjectNode)bad.path("payload").path("visit").path("attributes").get(0)).put(field,"incompatible");
            assertThrows(APIException.class,()->receiver().receiveEncounter(bad.toString()));
        }
        ObjectNode duplicate=original.deepCopy();ArrayNode attrs=(ArrayNode)duplicate.path("payload").path("visit").path("attributes");attrs.add(attrs.get(0).deepCopy());
        assertThrows(APIException.class,()->receiver().receiveEncounter(duplicate.toString()));
        assertEquals(0,receiver().getConfirmedEncounterSequence(origin));assertNull(saved(e));
    }
	
	@Test public void refusesAttributeUuidAlreadyOwnedByAnotherVisit()throws Exception {
        Encounter first=sample();receiver().receiveEncounter(json(first));
        Encounter other=sample();other.getVisit().getAttributes().iterator().next().setUuid(first.getVisit().getAttributes().iterator().next().getUuid());
        String incoming=new EncounterCreationPayloadSerializer().serialize(other,origin,2,UUID.randomUUID().toString(),new java.util.Date());
        assertThrows(APIException.class,()->receiver().receiveEncounter(incoming));assertNull(saved(other));
        assertEquals(1,receiver().getConfirmedEncounterSequence(origin));
    }
	
	private String historicalLocal(Encounter e)throws Exception {
        e.setVisit(Context.getVisitService().saveVisit(e.getVisit()));Context.getEncounterService().saveEncounter(e);sync().recordCreatedEncounter(e);
        Context.flushSession();
        try(PreparedStatement q=getConnection().prepareStatement("select payload_json from synchronizationmr_encounter_event where encounter_id=?")) {
            q.setInt(1,e.getEncounterId());try(ResultSet r=q.executeQuery()) {
                assertTrue(r.next());String old=legacy(r.getString(1));
                // Prepara una fila historica de prueba, como la que emitia el contrato anterior.
                try(PreparedStatement u=getConnection().prepareStatement("update synchronizationmr_encounter_event set payload_json=? where encounter_id=?")) {
                    u.setString(1,old);u.setInt(2,e.getEncounterId());u.executeUpdate();
                }return old;
            }
        }
    }
	
	@Test
	public void explicitPreparationIsIdempotentAndDoesNotConsumeSequence() throws Exception {
		Encounter e = sample();
		String old = historicalLocal(e);
		long before = sync().getHighestEncounterSequence("testServer_1");
		assertTrue(sync().prepareVisitAttributes(e.getUuid()));
		assertFalse(sync().prepareVisitAttributes(e.getUuid()));
		String wire = sync().getEncounterEventsAfter("testServer_1", before - 1, 1).get(0).getPayloadJson();
		assertEquals(old, new EncounterIncomingEvent(wire).json);
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
		e.getVisit().getAttributes().iterator().next().setValue("Nuevo valor posterior");
		assertFalse(sync().prepareVisitAttributes(e.getUuid()));
		assertEquals(wire, sync().getEncounterEventsAfter("testServer_1", before - 1, 1).get(0).getPayloadJson());
	}
	
	@Test public void preparationRejectsForeignCreateAndChangedVisitMetadata()throws Exception {
        Encounter remote=sample();receiver().receiveEncounter(json(remote));assertThrows(APIException.class,()->sync().prepareVisitAttributes(remote.getUuid()));
        Encounter local=sample();historicalLocal(local);local.getVisit().setStopDatetime(local.getEncounterDatetime());
        assertThrows(APIException.class,()->sync().prepareVisitAttributes(local.getUuid()));
    }
}

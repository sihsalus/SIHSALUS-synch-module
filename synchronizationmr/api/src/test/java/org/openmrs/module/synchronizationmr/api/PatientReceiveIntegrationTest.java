/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.api;

import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Credentials;
import org.openmrs.module.synchronizationmr.advice.PatientCreationAdvice;
import org.openmrs.module.synchronizationmr.sync.*;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

/** Simula recepción local con servicios reales. No levanta dos servidores ni utiliza HTTP. */
public class PatientReceiveIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private PatientCreationAdvice advice;
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new org.openmrs.GlobalProperty("server.id", "testServer_1"));
		advice = new PatientCreationAdvice();
		Context.addAdvice(PatientService.class, advice);
	}
	
	@AfterEach
	public void cleanup() {
		Context.removeAdvice(PatientService.class, advice);
	}
	
	private PatientReceiveService receiver() {
		return Context.getService(PatientReceiveService.class);
	}
	
	private PatientSyncService sync() {
		return Context.getService(PatientSyncService.class);
	}
	
	private Patient sample() {
		Patient patient = new Patient();
		patient.setGender("F");
		patient.setBirthdate(java.sql.Date.valueOf("2000-02-29"));
		PersonName name = new PersonName("María", null, "Prueba");
		name.setPreferred(true);
		patient.addName(name);
		PatientIdentifier identifier = new PatientIdentifier("DEMO-" + UUID.randomUUID(), Context.getPatientService()
		        .getPatientIdentifierType(2), Context.getLocationService().getLocation(1));
		identifier.setPreferred(true);
		patient.addIdentifier(identifier);
		PersonAddress address = new PersonAddress();
		address.setAddress1("Calle ficticia 123");
		address.setCountry("Perú");
		address.setPreferred(true);
		patient.addAddress(address);
		return patient;
	}
	
	private String event(Patient patient, String origin, long seq) {
		return new PatientCreationPayloadSerializer().serialize(patient, origin, seq, UUID.randomUUID().toString(),
		    new Date());
	}
	
	private PersonAttributeType attributeType(String format) {
		PersonAttributeType type = new PersonAttributeType();
		type.setName("Sync test " + UUID.randomUUID());
		type.setDescription("Fictitious attribute");
		type.setFormat(format);
		return Context.getPersonService().savePersonAttributeType(type);
	}
	
	@Test
	public void preservesBirthtimeAndAttributesIncludingReferenceAcrossDifferentLocalIds() throws Exception {
		Patient source = sample();
		source.setBirthtime(java.sql.Time.valueOf("08:23:47"));
		PersonAttribute text = new PersonAttribute(attributeType("java.lang.String"), "Contacto ficticio");
		source.addAttribute(text);
		PersonAttribute additional = new PersonAttribute(text.getAttributeType(), "Otro contacto ficticio");
		additional.setPerson(source);
		source.getAttributes().add(additional);
		PersonAttribute location = new PersonAttribute(attributeType("org.openmrs.Location"), "1");
		source.addAttribute(location);
		String origin = "posta_atributos";
		ObjectNode json = (ObjectNode) mapper.readTree(event(source, origin, 1));
		assertEquals(4, json.path("schemaVersion").asInt());
		assertEquals("08:23:47", json.path("payload").path("birthtime").asText());
		for (JsonNode item : json.path("payload").path("attributes")) {
			if (item.path("format").asText().equals("org.openmrs.Location")) {
				assertTrue(item.path("value").isNull());
				assertEquals(Context.getLocationService().getLocation(1).getUuid(), item.path("valueReferenceUuid").asText());
				// Simulate the catalog reference resolving to a different local row at the receiver.
				((ObjectNode) item).put("valueReferenceUuid", Context.getLocationService().getLocation(2).getUuid());
			}
		}
		assertEquals(1, receiver().receivePatient(json.toString()));
		Context.flushSession();
		Context.clearSession();
		Patient saved = Context.getPatientService().getPatientByUuid(source.getUuid());
		assertEquals("08:23:47", new java.text.SimpleDateFormat("HH:mm:ss").format(saved.getBirthtime()));
		assertEquals(3, saved.getActiveAttributes().size());
		assertEquals("Contacto ficticio", Context.getPersonService().getPersonAttributeByUuid(text.getUuid()).getValue());
		assertEquals("Otro contacto ficticio", Context.getPersonService().getPersonAttributeByUuid(additional.getUuid())
		        .getValue());
		assertEquals("2", saved.getAttribute(location.getAttributeType().getName()).getValue());
		assertEquals(1, receiver().receivePatient(json.toString()));
		assertEquals(json.toString(), sync().getCreationPayload(saved.getUuid()));
	}
	
	@Test
	public void acceptsHistoricalSchemaThreeWithoutRewritingSnapshot() throws Exception {
		Patient source = sample();
		ObjectNode json = (ObjectNode) mapper.readTree(event(source, "posta_legacy", 1));
		json.put("schemaVersion", 3);
		((ObjectNode) json.get("payload")).remove(Arrays.asList("attributes", "birthtime"));
		assertEquals(1, receiver().receivePatient(json.toString()));
		assertEquals(json.toString(), sync().getCreationPayload(source.getUuid()));
		assertEquals(1, receiver().receivePatient(json.toString()));
	}
	
	@Test
    public void rejectsMissingAttributeCatalogWithoutSavingOrConfirming() throws Exception {
        Patient source = sample();
        source.addAttribute(new PersonAttribute(attributeType("java.lang.String"), "Ejemplo"));
        ObjectNode json = (ObjectNode) mapper.readTree(event(source, "posta_invalid_attribute", 1));
        ((ObjectNode) json.path("payload").path("attributes").get(0)).put("attributeTypeUuid", UUID.randomUUID().toString());
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
        assertNull(Context.getPatientService().getPatientByUuid(source.getUuid()));
        assertEquals(0, receiver().getConfirmedPatientSequence("posta_invalid_attribute"));
    }
	
	@Test
    public void rejectsInvalidTimeAndMissingNewFields() throws Exception {
        Patient source = sample();
        ObjectNode json = (ObjectNode) mapper.readTree(event(source, "posta_invalid_time", 1));
        ObjectNode payload = (ObjectNode) json.get("payload");
        payload.put("birthtime", "25:00:00");
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
        payload.putNull("birthtime");
        payload.remove("attributes");
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
        assertNull(Context.getPatientService().getPatientByUuid(source.getUuid()));
        assertEquals(0, receiver().getConfirmedPatientSequence("posta_invalid_time"));
    }
	
	@Test
	public void rejectsEquivalentNamesRatherThanSilentlyDroppingOne() throws Exception {
		rejectsEquivalentChild("names");
	}
	
	@Test
	public void rejectsEquivalentIdentifiersRatherThanSilentlyDroppingOne() throws Exception {
		rejectsEquivalentChild("identifiers");
	}
	
	@Test
	public void rejectsEquivalentAddressesRatherThanSilentlyDroppingOne() throws Exception {
		rejectsEquivalentChild("addresses");
	}
	
	private void rejectsEquivalentChild(String collection) throws Exception {
        Patient source = sample();
        String origin = "posta_equivalent_" + collection;
        ObjectNode json = (ObjectNode) mapper.readTree(event(source, origin, 1));
        com.fasterxml.jackson.databind.node.ArrayNode children =
            (com.fasterxml.jackson.databind.node.ArrayNode) json.path("payload").path(collection);
        ObjectNode duplicate = ((ObjectNode) children.get(0)).deepCopy();
        duplicate.put("uuid", UUID.randomUUID().toString());
        children.add(duplicate);
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
        assertNull(Context.getPatientService().getPatientByUuid(source.getUuid()));
        assertNull(sync().getByPatientUuid(source.getUuid()));
        assertEquals(0, receiver().getConfirmedPatientSequence(origin));
    }
	
	private long localSequence() throws Exception {
        try (java.sql.Statement query = getConnection().createStatement();
                java.sql.ResultSet rows = query.executeQuery("select patient_sequence from synchronizationmr_local_node where singleton_id = 1")) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
	
	@Test
	public void receivesPatientAndPreservesOriginForRedistribution() throws Exception {
		String local = Context.getService(LocalNodeService.class).getLocalServerId();
		long before = localSequence();
		String origin = "posta_" + UUID.randomUUID().toString();
		Patient source = sample();
		String json = event(source, origin, 1);
		assertEquals(1, receiver().receivePatient(json));
		Patient saved = Context.getPatientService().getPatientByUuid(source.getUuid());
		assertNotNull(saved);
		PatientSyncRecord reused = sync().ensurePatientSyncRecord(saved.getPatientId());
		assertEquals(origin, reused.getOriginServerId());
		assertEquals(1, reused.getSequence());
		assertEquals(json, sync().getCreationPayload(saved.getUuid()));
		assertEquals("María", saved.getGivenName());
		assertEquals(source.getPersonName().getUuid(), saved.getPersonName().getUuid());
		assertEquals(source.getPersonAddress().getUuid(), saved.getPersonAddress().getUuid());
		assertEquals("Calle ficticia 123", saved.getPersonAddress().getAddress1());
		assertEquals(source.getPatientIdentifier().getUuid(), saved.getPatientIdentifier().getUuid());
		assertEquals(source.getBirthdate(), saved.getBirthdate());
		assertEquals(origin, sync().getByPatientUuid(saved.getUuid()).getOriginServerId());
		assertEquals(json, sync().getPatientEventsAfter(origin, 0, 10).get(0).getPayloadJson());
		assertEquals(before, localSequence());
		assertNotEquals(local, origin);
		assertEquals(1, receiver().getConfirmedPatientSequence(origin));
		ObjectNode evidence = mapper.createObjectNode();
		evidence.put("nota", "Recepción local de un paciente ficticio; no hay conexión entre servidores");
		evidence.put("uuidPacienteEnviado", source.getUuid());
		evidence.put("uuidPacienteRecibido", saved.getUuid());
		evidence.put("serverIdOrigenEnviado", origin);
		evidence.put("serverIdOrigenGuardado", sync().getByPatientUuid(saved.getUuid()).getOriginServerId());
		evidence.put("serverIdReceptor", local);
		evidence.put("secuenciaConfirmada", receiver().getConfirmedPatientSequence(origin));
		evidence.put("contadorLocalAntes", before);
		evidence.put("contadorLocalDespues", localSequence());
		java.nio.file.Path directory = java.nio.file.Paths.get("target", "demo-sincronizacion");
		java.nio.file.Files.createDirectories(directory);
		mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("recepcion-paciente.json").toFile(), evidence);
		System.out
		        .println("RECEPCIÓN LOCAL VERIFICADA: paciente y origen conservados | confirmación=1 | sin crear evento local");
	}
	
	@Test
	public void duplicateDeliveryReturnsConfirmationWithoutRewritingPatient() {
		String origin = "posta_" + UUID.randomUUID().toString();
		Patient patient = sample();
		String json = event(patient, origin, 1);
		assertEquals(1, receiver().receivePatient(json));
		Integer id = Context.getPatientService().getPatientByUuid(patient.getUuid()).getPatientId();
		assertEquals(1, receiver().receivePatient(json));
		assertEquals(id, Context.getPatientService().getPatientByUuid(patient.getUuid()).getPatientId());
		assertEquals(1, sync().getPatientEventsAfter(origin, 0, 10).size());
	}
	
	@Test
    public void cannotConfirmThreeWhenTwoIsMissing() {
        String origin = "posta_" + UUID.randomUUID().toString();
        receiver().receivePatient(event(sample(), origin, 1));
        Patient third = sample();
        assertThrows(APIException.class, () -> receiver().receivePatient(event(third, origin, 3)));
        assertNull(Context.getPatientService().getPatientByUuid(third.getUuid()));
        assertEquals(1, receiver().getConfirmedPatientSequence(origin));
    }
	
	@Test
    public void missingCatalogDoesNotSavePatientOrAdvanceConfirmation() throws Exception {
        String origin = "posta_" + UUID.randomUUID().toString();
        Patient patient = sample();
        ObjectNode json = (ObjectNode) mapper.readTree(event(patient, origin, 1));
        ((ObjectNode) json.get("payload").get("identifiers").get(0)).put("identifierTypeUuid", UUID.randomUUID().toString());
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
        assertNull(Context.getPatientService().getPatientByUuid(patient.getUuid()));
        assertEquals(0, receiver().getConfirmedPatientSequence(origin));
    }
	
	@Test
    public void differentContentForConfirmedSequenceIsRejected() throws Exception {
        String origin = "posta_" + UUID.randomUUID().toString();
        String json = event(sample(), origin, 1);
        receiver().receivePatient(json);
        ObjectNode changed = (ObjectNode) mapper.readTree(json);
        ((ObjectNode) changed.get("payload").get("names").get(0)).put("givenName", "Cambio no autorizado");
        assertThrows(APIException.class, () -> receiver().receivePatient(changed.toString()));
        assertEquals(1, receiver().getConfirmedPatientSequence(origin));
    }
	
	@Test
    public void duplicateEventUuidRollsBackClinicalSaveAndConfirmationWithoutOuterTransaction() throws Exception {
        TestTransaction.flagForCommit(); TestTransaction.end();
        try {
            String origin = "posta_" + UUID.randomUUID().toString();
            ObjectNode first = (ObjectNode) mapper.readTree(event(sample(), origin, 1));
            receiver().receivePatient(first.toString());
            Patient second = sample();
            ObjectNode bad = (ObjectNode) mapper.readTree(event(second, origin, 2));
            bad.put("eventUuid", first.get("eventUuid").asText());
            assertThrows(RuntimeException.class, () -> receiver().receivePatient(bad.toString()));
            assertNull(Context.getPatientService().getPatientByUuid(second.getUuid()));
            assertNull(sync().getByPatientUuid(second.getUuid()));
            assertEquals(1, receiver().getConfirmedPatientSequence(origin));
            assertEquals(2, receiver().receivePatient(event(second, origin, 2)));
            // El guardado normal sigue capturándose después de un fallo de recepción.
            Patient normal = Context.getPatientService().savePatient(sample());
            assertEquals(Context.getService(LocalNodeService.class).getLocalServerId(),
                    sync().getByPatientUuid(normal.getUuid()).getOriginServerId());
        } finally { TestTransaction.start(); }
    }
	
	@Test
    public void rejectsExistingPatientWithoutOverwritingOrConfirming() {
        Patient local = Context.getPatientService().savePatient(sample());
        String origin = "posta_" + UUID.randomUUID().toString();
        assertThrows(APIException.class, () -> receiver().receivePatient(event(local, origin, 1)));
        assertEquals(0, receiver().getConfirmedPatientSequence(origin));
        assertNotEquals(origin, sync().getByPatientUuid(local.getUuid()).getOriginServerId());
    }
	
	@Test
    public void simultaneousDuplicateDeliveryCreatesSingleRecord() throws Exception {
        Credentials credentials = getCredentials();
        String origin = "posta_" + UUID.randomUUID().toString();
        String json = event(sample(), origin, 1);
        TestTransaction.flagForCommit(); TestTransaction.end();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            Callable<Long> task = () -> {
                Context.openSession();
                try {
                    Context.authenticate(credentials); ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("La recepción simultánea no inició"); }
                    return receiver().receivePatient(json);
                } finally { Context.closeSession(); }
            };
            Future<Long> a = pool.submit(task), b = pool.submit(task);
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            assertEquals(1L, a.get(20, TimeUnit.SECONDS).longValue());
            assertEquals(1L, b.get(20, TimeUnit.SECONDS).longValue());
            assertEquals(1, sync().getPatientEventsAfter(origin, 0, 10).size());
        } finally { start.countDown(); pool.shutdownNow(); TestTransaction.start(); }
    }
	
	@Test
	public void rejectsMalformedUnsupportedAndUnknownFields() throws Exception {
        assertThrows(APIException.class, () -> receiver().receivePatient("{}"));
        ObjectNode json = (ObjectNode) mapper.readTree(event(sample(), UUID.randomUUID().toString(), 1));
        json.put("schemaVersion", 99);
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
        json.put("schemaVersion", 4); json.put("extra", "no admitido");
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
        json.remove("extra"); ((ObjectNode) json.get("payload")).remove("addresses");
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
	}
	
	@Test
	public void rejectsOldNodeUuidSchemaWithoutSavingPatient() throws Exception {
		String origin = "posta_" + UUID.randomUUID().toString();
		Patient source = sample();
		source.setUuid("paciente-" + UUID.randomUUID().toString().substring(0, 20));
		ObjectNode json = (ObjectNode) mapper.readTree(event(source, origin, 1));
		json.put("schemaVersion", 1);
		((ObjectNode) json.get("payload")).remove("addresses");
		json.remove("originServerId");
		json.put("originNodeUuid", UUID.randomUUID().toString());
		assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
		assertNull(Context.getPatientService().getPatientByUuid(source.getUuid()));
	}
	
	@Test
    public void unauthenticatedCallerCannotImportPatient() {
        Credentials credentials = getCredentials();
        String origin = "posta_" + UUID.randomUUID().toString();
        Patient source = sample();
        String json = event(source, origin, 1);
        PatientReceiveService service = receiver();
        Context.logout();
        try {
            assertFalse(Context.isAuthenticated());
            assertThrows(APIException.class, () -> service.receivePatient(json));
        } finally { Context.authenticate(credentials); }
        assertNull(Context.getPatientService().getPatientByUuid(source.getUuid()));
        assertEquals(0, receiver().getConfirmedPatientSequence(origin));
    }
	
	@Test
    public void preservesAllAddressFieldsAndMultipleNamesAfterDatabaseReload() throws Exception {
        Patient source = sample();
        PersonName alternative = new PersonName("María Elena", "Ejemplo", "Prueba alternativa");
        alternative.setPreferred(false);
        alternative.setFamilyName2("Segundo apellido");
        source.addName(alternative);
        PersonAddress address = source.getPersonAddress();
        org.springframework.beans.BeanWrapper fields = new org.springframework.beans.BeanWrapperImpl(address);
        for (int i = 1; i <= 15; i++) fields.setPropertyValue("address" + i, "Campo ficticio " + i);
        address.setCountry("Perú"); address.setStateProvince("Región ficticia");
        address.setCountyDistrict("Distrito ficticio"); address.setCityVillage("Villa ficticia");
        address.setPostalCode("15001"); address.setLatitude("-12.0464"); address.setLongitude("-77.0428");
        address.setStartDate(Date.from(java.time.Instant.parse("2020-01-02T12:30:00Z")));
        address.setEndDate(Date.from(java.time.Instant.parse("2025-01-02T12:30:00Z")));
        PersonAddress second = new PersonAddress(); second.setAddress1("Otra dirección ficticia");
        second.setPreferred(false); source.addAddress(second);
        String origin = "posta_" + UUID.randomUUID();
        String json = event(source, origin, 1);
        receiver().receivePatient(json); Context.flushSession(); Context.clearSession();
        Patient saved = Context.getPatientService().getPatientByUuid(source.getUuid());
        JsonNode expected = mapper.readTree(json).get("payload");
        JsonNode actual = mapper.readTree(event(saved, origin, 1)).get("payload");
        for (String collection : Arrays.asList("names", "addresses", "identifiers")) {
            Map<String, JsonNode> before = new TreeMap<>(), after = new TreeMap<>();
            for (JsonNode value : expected.get(collection)) before.put(value.get("uuid").asText(), value);
            for (JsonNode value : actual.get(collection)) after.put(value.get("uuid").asText(), value);
            assertEquals(before, after, collection + " must survive native persistence without losing fields");
        }
        assertEquals(2, saved.getNames().size()); assertEquals(2, saved.getAddresses().size());
        assertEquals("2000-02-29", actual.get("birthdate").asText());
        assertEquals(json, sync().getCreationPayload(source.getUuid()));
    }
	
	@Test
	public void retryDoesNotOverwriteLocalCorrectionOrReplaceOriginalSnapshot() throws Exception {
		Patient source = sample();
		String origin = "posta_" + UUID.randomUUID();
		String json = event(source, origin, 1);
		receiver().receivePatient(json);
		Patient local = Context.getPatientService().getPatientByUuid(source.getUuid());
		Integer localId = local.getPatientId();
		local.getPersonAddress().setAddress1("Dirección corregida localmente");
		Context.getPatientService().savePatient(local);
		Context.flushSession();
		Context.clearSession();
		long before = localSequence();
		assertEquals(1, receiver().receivePatient(json));
		Context.clearSession();
		Patient reloaded = Context.getPatientService().getPatientByUuid(source.getUuid());
		assertEquals(localId, reloaded.getPatientId());
		assertEquals("Dirección corregida localmente", reloaded.getPersonAddress().getAddress1());
		assertEquals(json, sync().getCreationPayload(source.getUuid()));
		assertEquals(before, localSequence());
		assertEquals(1, sync().getPatientEventsAfter(origin, 0, 100).size());
	}
	
}

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
	
	private long localSequence() throws Exception {
        try (java.sql.Statement query = getConnection().createStatement();
                java.sql.ResultSet rows = query.executeQuery("select patient_sequence from synchronizationmr_local_node where singleton_id = 1")) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
	
	@Test
	public void receivesPatientAndPreservesOriginForRedistribution() throws Exception {
		String local = Context.getService(LocalNodeService.class).getOrCreateNodeUuid();
		long before = localSequence();
		String origin = UUID.randomUUID().toString();
		Patient source = sample();
		String json = event(source, origin, 1);
		assertEquals(1, receiver().receivePatient(json));
		Patient saved = Context.getPatientService().getPatientByUuid(source.getUuid());
		assertNotNull(saved);
		assertEquals("María", saved.getGivenName());
		assertEquals(source.getPersonName().getUuid(), saved.getPersonName().getUuid());
		assertEquals(source.getPersonAddress().getUuid(), saved.getPersonAddress().getUuid());
		assertEquals("Calle ficticia 123", saved.getPersonAddress().getAddress1());
		assertEquals(source.getPatientIdentifier().getUuid(), saved.getPatientIdentifier().getUuid());
		assertEquals(source.getBirthdate(), saved.getBirthdate());
		assertEquals(origin, sync().getByPatientUuid(saved.getUuid()).getOriginNodeUuid());
		assertEquals(json, sync().getPatientEventsAfter(origin, 0, 10).get(0).getPayloadJson());
		assertEquals(before, localSequence());
		assertNotEquals(local, origin);
		assertEquals(1, receiver().getConfirmedPatientSequence(origin));
		ObjectNode evidence = mapper.createObjectNode();
		evidence.put("nota", "Recepción local de un paciente ficticio; no hay conexión entre servidores");
		evidence.put("uuidPacienteEnviado", source.getUuid());
		evidence.put("uuidPacienteRecibido", saved.getUuid());
		evidence.put("uuidOrigenEnviado", origin);
		evidence.put("uuidOrigenGuardado", sync().getByPatientUuid(saved.getUuid()).getOriginNodeUuid());
		evidence.put("uuidNodoReceptor", local);
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
		String origin = UUID.randomUUID().toString();
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
        String origin = UUID.randomUUID().toString();
        receiver().receivePatient(event(sample(), origin, 1));
        Patient third = sample();
        assertThrows(APIException.class, () -> receiver().receivePatient(event(third, origin, 3)));
        assertNull(Context.getPatientService().getPatientByUuid(third.getUuid()));
        assertEquals(1, receiver().getConfirmedPatientSequence(origin));
    }
	
	@Test
    public void missingCatalogDoesNotSavePatientOrAdvanceConfirmation() throws Exception {
        String origin = UUID.randomUUID().toString();
        Patient patient = sample();
        ObjectNode json = (ObjectNode) mapper.readTree(event(patient, origin, 1));
        ((ObjectNode) json.get("payload").get("identifiers").get(0)).put("identifierTypeUuid", UUID.randomUUID().toString());
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
        assertNull(Context.getPatientService().getPatientByUuid(patient.getUuid()));
        assertEquals(0, receiver().getConfirmedPatientSequence(origin));
    }
	
	@Test
    public void differentContentForConfirmedSequenceIsRejected() throws Exception {
        String origin = UUID.randomUUID().toString();
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
            String origin = UUID.randomUUID().toString();
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
            assertEquals(Context.getService(LocalNodeService.class).getOrCreateNodeUuid(),
                    sync().getByPatientUuid(normal.getUuid()).getOriginNodeUuid());
        } finally { TestTransaction.start(); }
    }
	
	@Test
    public void rejectsExistingPatientWithoutOverwritingOrConfirming() {
        Patient local = Context.getPatientService().savePatient(sample());
        String origin = UUID.randomUUID().toString();
        assertThrows(APIException.class, () -> receiver().receivePatient(event(local, origin, 1)));
        assertEquals(0, receiver().getConfirmedPatientSequence(origin));
        assertNotEquals(origin, sync().getByPatientUuid(local.getUuid()).getOriginNodeUuid());
    }
	
	@Test
    public void simultaneousDuplicateDeliveryCreatesSingleRecord() throws Exception {
        Credentials credentials = getCredentials();
        String origin = UUID.randomUUID().toString();
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
        json.put("schemaVersion", 3);
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
        json.put("schemaVersion", 2); json.put("extra", "no admitido");
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
        json.remove("extra"); ((ObjectNode) json.get("payload")).remove("addresses");
        assertThrows(APIException.class, () -> receiver().receivePatient(json.toString()));
	}
	
	@Test
	public void acceptsVersionOneWithoutInventingAddresses() throws Exception {
		String origin = UUID.randomUUID().toString();
		Patient source = sample();
		source.setUuid("paciente-" + UUID.randomUUID().toString().substring(0, 20));
		ObjectNode json = (ObjectNode) mapper.readTree(event(source, origin, 1));
		json.put("schemaVersion", 1);
		((ObjectNode) json.get("payload")).remove("addresses");
		assertEquals(1, receiver().receivePatient(json.toString()));
		assertTrue(Context.getPatientService().getPatientByUuid(source.getUuid()).getAddresses().isEmpty());
		assertEquals(json.toString(), sync().getCreationPayload(source.getUuid()));
	}
	
	@Test
    public void unauthenticatedCallerCannotImportPatient() {
        Credentials credentials = getCredentials();
        String origin = UUID.randomUUID().toString();
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
}

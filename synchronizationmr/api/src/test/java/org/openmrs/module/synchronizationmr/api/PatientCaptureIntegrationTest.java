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

import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PersonName;
import org.openmrs.Person;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Credentials;
import org.openmrs.module.synchronizationmr.advice.PatientCreationAdvice;
import org.openmrs.module.synchronizationmr.sync.PatientSyncRecord;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.transaction.TestTransaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PatientCaptureIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private PatientCreationAdvice advice;
	
	@Test
	public void queriesOrderedPagesWithoutChangingPendingEvents() {
		Patient a = Context.getPatientService().savePatient(newPatient());
		Patient b = Context.getPatientService().savePatient(newPatient());
		Patient c = Context.getPatientService().savePatient(newPatient());
		PatientSyncRecord first = sync().getByPatientUuid(a.getUuid());
		String origin = first.getOriginNodeUuid();
		java.util.List<org.openmrs.module.synchronizationmr.sync.PatientSyncEvent> page = sync().getPatientEventsAfter(
		    origin, first.getSequence() - 1, 2);
		assertEquals(2, page.size());
		assertEquals(a.getUuid(), page.get(0).getPatientUuid());
		assertEquals(b.getUuid(), page.get(1).getPatientUuid());
		assertEquals(first.getEventUuid(), page.get(0).getEventUuid());
		assertEquals(sync().getCreationPayload(a.getUuid()), page.get(0).getPayloadJson());
		java.util.List<org.openmrs.module.synchronizationmr.sync.PatientSyncEvent> next = sync().getPatientEventsAfter(
		    origin, page.get(1).getSequence(), 2);
		assertEquals(1, next.size());
		assertEquals(c.getUuid(), next.get(0).getPatientUuid());
		assertEquals(next.get(0).getSequence(), sync().getHighestPatientSequence(origin));
		assertTrue(sync().getPatientEventsAfter(origin, next.get(0).getSequence(), 2).isEmpty());
		assertEquals("PENDING", sync().getByPatientUuid(a.getUuid()).getState());
		assertEquals("PENDING", sync().getByPatientUuid(b.getUuid()).getState());
		assertEquals("PENDING", sync().getByPatientUuid(c.getUuid()).getState());
		System.out.println("CONSULTA VERIFICADA: primera página=2 | segunda página=1 | sin confirmar entregas");
	}
	
	@Test
    public void missingPayloadAbortsPageInsteadOfSkippingEvent() throws Exception {
        Patient a = Context.getPatientService().savePatient(newPatient());
        Patient b = Context.getPatientService().savePatient(newPatient());
        Context.getPatientService().savePatient(newPatient());
        PatientSyncRecord first = sync().getByPatientUuid(a.getUuid());
        try (java.sql.PreparedStatement query = getConnection().prepareStatement(
                "update synchronizationmr_patient_event set payload_json = null where patient_id = ?")) {
            query.setInt(1, b.getPatientId());
            query.executeUpdate();
        }
        assertThrows(org.openmrs.api.APIException.class, () -> sync().getPatientEventsAfter(
                first.getOriginNodeUuid(), first.getSequence() - 1, 3));
    }
	
	@Test
    public void missingEventIsNotHiddenByQuery() throws Exception {
        Patient patient = Context.getPatientService().savePatient(newPatient());
        PatientSyncRecord record = sync().getByPatientUuid(patient.getUuid());
        try (java.sql.PreparedStatement query = getConnection().prepareStatement(
                "delete from synchronizationmr_patient_event where patient_id = ?")) {
            query.setInt(1, patient.getPatientId());
            query.executeUpdate();
        }
        assertEquals(record.getSequence(), sync().getHighestPatientSequence(record.getOriginNodeUuid()));
        assertThrows(org.openmrs.api.APIException.class, () -> sync().getPatientEventsAfter(
                record.getOriginNodeUuid(), record.getSequence() - 1, 1));
    }
	
	@Test
    public void higherSequenceDoesNotHideGap() throws Exception {
        Patient patient = Context.getPatientService().savePatient(newPatient());
        PatientSyncRecord record = sync().getByPatientUuid(patient.getUuid());
        // Solo en la base de pruebas: simula una identidad ausente antes de otra disponible.
        try (java.sql.PreparedStatement query = getConnection().prepareStatement(
                "update synchronizationmr_patient_identity set entity_sequence = ? where patient_id = ?")) {
            query.setLong(1, record.getSequence() + 1);
            query.setInt(2, patient.getPatientId());
            query.executeUpdate();
        }
        assertEquals(record.getSequence() + 1, sync().getHighestPatientSequence(record.getOriginNodeUuid()));
        assertThrows(org.openmrs.api.APIException.class, () -> sync().getPatientEventsAfter(
                record.getOriginNodeUuid(), record.getSequence() - 1, 2));
    }
	
	@Test
    public void queriesSeparateOriginsAndRejectInvalidArguments() throws Exception {
        Patient a = Context.getPatientService().savePatient(newPatient());
        Patient b = Context.getPatientService().savePatient(newPatient());
        PatientSyncRecord local = sync().getByPatientUuid(a.getUuid());
        PatientSyncRecord foreign = sync().getByPatientUuid(b.getUuid());
        String otherOrigin = UUID.randomUUID().toString();
        // Simula un registro ya importado; todavía no implementa la recepción por red.
        try (java.sql.PreparedStatement query = getConnection().prepareStatement(
                "update synchronizationmr_patient_identity set origin_node_uuid = ?, entity_sequence = 1 where patient_id = ?")) {
            query.setString(1, otherOrigin);
            query.setInt(2, b.getPatientId());
            query.executeUpdate();
        }
        String json = new org.openmrs.module.synchronizationmr.sync.PatientCreationPayloadSerializer()
                .serialize(b, otherOrigin, 1, foreign.getEventUuid(), foreign.getDateCreated());
        try (java.sql.PreparedStatement query = getConnection().prepareStatement(
                "update synchronizationmr_patient_event set payload_json = ? where patient_id = ?")) {
            query.setString(1, json);
            query.setInt(2, b.getPatientId());
            query.executeUpdate();
        }
        assertEquals(local.getSequence(), sync().getHighestPatientSequence(local.getOriginNodeUuid()));
        assertEquals(1, sync().getHighestPatientSequence(otherOrigin));
        assertEquals(b.getUuid(), sync().getPatientEventsAfter(otherOrigin, 0, 10).get(0).getPatientUuid());
        assertEquals(1, sync().getPatientEventsAfter(local.getOriginNodeUuid(), local.getSequence() - 1, 10).size());
        String unknown = UUID.randomUUID().toString();
        assertEquals(0, sync().getHighestPatientSequence(unknown));
        assertTrue(sync().getPatientEventsAfter(unknown, 0, 1).isEmpty());
        assertTrue(sync().getPatientEventsAfter(otherOrigin, Long.MAX_VALUE, 1).isEmpty());
        assertThrows(org.openmrs.api.APIException.class, () -> sync().getPatientEventsAfter(otherOrigin, -1, 1));
        assertThrows(org.openmrs.api.APIException.class, () -> sync().getPatientEventsAfter(otherOrigin, 0, 0));
        assertThrows(org.openmrs.api.APIException.class, () -> sync().getPatientEventsAfter(otherOrigin, 0, 101));
        assertThrows(org.openmrs.api.APIException.class, () -> sync().getHighestPatientSequence("POSTA-01"));
    }
	
	@Autowired
	private org.openmrs.module.synchronizationmr.api.dao.PatientSyncDao patientSyncDao;
	
	@Test
	public void creationPayloadPreservesIdentityAndClinicalReferences() throws Exception {
		Patient patient = newPatient();
		patient.getPersonName().setGivenName("María \"Luz\"");
		patient.setBirthdate(new java.text.SimpleDateFormat("yyyy-MM-dd").parse("2000-02-29"));
		patient.setBirthdateEstimated(true);
		org.openmrs.PersonAddress address = new org.openmrs.PersonAddress();
		address.setAddress1("Calle de prueba 123");
		address.setCityVillage("Santa Clotilde");
		address.setStateProvince("Loreto");
		address.setCountry("Perú");
		address.setPreferred(true);
		patient.addAddress(address);
		Context.getPatientService().savePatient(patient);
		PatientSyncRecord record = sync().getByPatientUuid(patient.getUuid());
		com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(sync()
		        .getCreationPayload(patient.getUuid()));
		assertEquals(2, json.get("schemaVersion").asInt());
		assertEquals(record.getEventUuid(), json.get("eventUuid").asText());
		assertEquals(record.getOriginNodeUuid(), json.get("originNodeUuid").asText());
		assertEquals(record.getSequence(), json.get("entitySequence").asLong());
		assertEquals("PATIENT", json.get("entityType").asText());
		assertEquals("CREATE", json.get("operation").asText());
		assertNotNull(java.time.Instant.parse(json.get("occurredAt").asText()));
		com.fasterxml.jackson.databind.JsonNode data = json.get("payload");
		assertEquals(patient.getUuid(), data.get("patientUuid").asText());
		assertEquals("2000-02-29", data.get("birthdate").asText());
		assertTrue(data.get("birthdateEstimated").asBoolean());
		assertTrue(data.get("deathDate").isNull());
		assertEquals("María \"Luz\"", data.get("names").get(0).get("givenName").asText());
		assertEquals(patient.getPatientIdentifier().getIdentifierType().getUuid(),
		    data.get("identifiers").get(0).get("identifierTypeUuid").asText());
		assertEquals(patient.getPatientIdentifier().getLocation().getUuid(),
		    data.get("identifiers").get(0).get("locationUuid").asText());
		assertFalse(data.has("patientId"));
		assertFalse(data.has("creator"));
		assertEquals(1, data.get("addresses").size());
		assertEquals(address.getUuid(), data.get("addresses").get(0).get("uuid").asText());
		assertEquals("Calle de prueba 123", data.get("addresses").get(0).get("address1").asText());
		assertEquals("Perú", data.get("addresses").get(0).get("country").asText());
		// La copia original tampoco cambia al editar una dirección después del registro.
		String snapshot = sync().getCreationPayload(patient.getUuid());
		address.setAddress1("Dirección posterior");
		Context.getPatientService().savePatient(patient);
		assertEquals(snapshot, sync().getCreationPayload(patient.getUuid()));
		
		// Evidencia exportada únicamente por esta prueba, que crea un paciente ficticio.
		// El JSON procede de la consulta al evento guardado, no de reconstruir al paciente.
		String nodeUuid = Context.getService(LocalNodeService.class).getOrCreateNodeUuid();
		assertEquals(nodeUuid, json.get("originNodeUuid").asText());
		java.nio.file.Path directory = java.nio.file.Paths.get("target", "demo-sincronizacion");
		java.nio.file.Files.createDirectories(directory);
		com.fasterxml.jackson.databind.ObjectMapper evidenceMapper = new com.fasterxml.jackson.databind.ObjectMapper();
		evidenceMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("paciente-creacion.json").toFile(),
		    json);
		com.fasterxml.jackson.databind.node.ObjectNode comparison = evidenceMapper.createObjectNode();
		comparison.put("nota", "Datos ficticios de una prueba local; no demuestra entrega al maestro");
		comparison.put("patientIdLocal", patient.getPatientId());
		comparison.put("uuidPacienteOpenMRS", patient.getUuid());
		comparison.put("uuidPacienteEnJson", data.get("patientUuid").asText());
		comparison.put("uuidNodoLocal", nodeUuid);
		comparison.put("uuidOrigenEnJson", json.get("originNodeUuid").asText());
		comparison.put("uuidEventoGuardado", record.getEventUuid());
		comparison.put("uuidEventoEnJson", json.get("eventUuid").asText());
		comparison.put("identificadorVisible", record.getDisplayIdentifier());
		comparison.put("secuenciaGuardada", record.getSequence());
		comparison.put("secuenciaEnJson", json.get("entitySequence").asLong());
		evidenceMapper.writerWithDefaultPrettyPrinter().writeValue(
		    directory.resolve("comparacion-identificadores.json").toFile(), comparison);
		
		System.out.println("EVIDENCIA FICTICIA: " + directory.toAbsolutePath());
		System.out.println("JSON DE CREACIÓN VERIFICADO: versión=2 | direcciones guardadas | UUID conservado");
	}
	
	@Test
	public void editingPatientDoesNotRewriteOriginalPayload() {
		Patient patient = Context.getPatientService().savePatient(newPatient());
		String original = sync().getCreationPayload(patient.getUuid());
		assertNotNull(original);
		patient.getPersonName().setGivenName("Nombre posterior");
		Context.getPatientService().savePatient(patient);
		sync().recordCreatedPatient(patient);
		assertEquals(original, sync().getCreationPayload(patient.getUuid()));
	}
	
	@Test
    public void legacyEventWithoutPayloadIsNotReconstructedFromCurrentPatient() throws Exception {
        Patient patient = Context.getPatientService().savePatient(newPatient());
        try (java.sql.PreparedStatement statement = getConnection().prepareStatement(
                "update synchronizationmr_patient_event set payload_json = null where patient_id = ?")) {
            statement.setInt(1, patient.getPatientId());
            statement.executeUpdate();
        }
        assertNotNull(sync().getByPatientUuid(patient.getUuid()));
        sync().recordCreatedPatient(patient);
        assertNull(sync().getCreationPayload(patient.getUuid()));
    }
	
	@Test
    public void serializationFailureRollsBackPatientIdentityAndCounter() {
        Object original = org.springframework.test.util.ReflectionTestUtils.getField(patientSyncDao, "payloadSerializer");
        org.openmrs.module.synchronizationmr.sync.PatientCreationPayloadSerializer failing =
                mock(org.openmrs.module.synchronizationmr.sync.PatientCreationPayloadSerializer.class);
        when(failing.serialize(any(), anyString(), anyLong(), anyString(), any()))
                .thenThrow(new org.openmrs.api.APIException("Error simulado al construir el JSON"));
        TestTransaction.flagForCommit();
        TestTransaction.end();
        try {
            Patient prior = Context.getPatientService().savePatient(newPatient());
            long sequence = sync().getByPatientUuid(prior.getUuid()).getSequence();
            org.springframework.test.util.ReflectionTestUtils.setField(patientSyncDao, "payloadSerializer", failing);
            Patient failed = newPatient();
            assertThrows(org.openmrs.api.APIException.class,
                    () -> Context.getPatientService().savePatient(failed));
            assertNull(Context.getPatientService().getPatientByUuid(failed.getUuid()));
            assertNull(sync().getByPatientUuid(failed.getUuid()));
            assertNull(sync().getCreationPayload(failed.getUuid()));
            org.springframework.test.util.ReflectionTestUtils.setField(patientSyncDao, "payloadSerializer", original);
            Patient next = Context.getPatientService().savePatient(newPatient());
            assertEquals(sequence + 1, sync().getByPatientUuid(next.getUuid()).getSequence());
        }
        finally {
            org.springframework.test.util.ReflectionTestUtils.setField(patientSyncDao, "payloadSerializer", original);
            TestTransaction.start();
        }
    }
	
	@Test
	public void patientUsesPreviouslyInitializedNodeUuid() {
		String uuid = Context.getService(LocalNodeService.class).getOrCreateNodeUuid();
		Patient patient = Context.getPatientService().savePatient(newPatient());
		assertEquals(uuid, sync().getByPatientUuid(patient.getUuid()).getOriginNodeUuid());
	}
	
	@Test
	public void capturesWithoutAnOuterTestTransaction() {
		// Sin transacción externa de prueba: el interceptor debe agrupar el guardado y el evento.
		TestTransaction.flagForCommit();
		TestTransaction.end();
		try {
			Patient patient = Context.getPatientService().savePatient(newPatient());
			PatientSyncRecord record = sync().getByPatientUuid(patient.getUuid());
			assertNotNull(record);
			System.out.println("CAPTURA CONFIRMADA: " + record.getDisplayIdentifier() + " | origen="
			        + record.getOriginNodeUuid() + " | estado="
			        + ("PENDING".equals(record.getState()) ? "PENDIENTE" : record.getState()));
		}
		finally {
			TestTransaction.start();
		}
	}
	
	@Test
	public void capturesExistingPersonBecomingPatient() {
		Person person = new Person();
		person.setGender("F");
		person.addName(new PersonName("Persona", null, "Prueba"));
		Context.getPersonService().savePerson(person);
		Patient patient = new Patient(person);
		patient.addIdentifier(newPatient().getPatientIdentifier());
		Context.getPatientService().savePatient(patient);
		assertEquals(person.getPersonId(), patient.getPatientId());
		assertNotNull(sync().getByPatientUuid(patient.getUuid()));
	}
	
	@Test
    public void simultaneousClinicalTransactionsAllocateDifferentSequences() throws Exception {
        Credentials credentials = getCredentials();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<PatientSyncRecord> create = () -> {
                Context.openSession();
                try {
                    Context.authenticate(credentials);
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("La prueba de registros simultáneos no pudo iniciarse");
                    }
                    Patient patient = Context.getPatientService().savePatient(newPatient());
                    return sync().getByPatientUuid(patient.getUuid());
                }
                finally {
                    Context.closeSession();
                }
            };
            Future<PatientSyncRecord> first = pool.submit(create);
            Future<PatientSyncRecord> second = pool.submit(create);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            PatientSyncRecord a = first.get(20, TimeUnit.SECONDS);
            PatientSyncRecord b = second.get(20, TimeUnit.SECONDS);
            assertEquals(a.getOriginNodeUuid(), b.getOriginNodeUuid());
            assertEquals(1L, Math.abs(a.getSequence() - b.getSequence()));
            assertNotEquals(a.getEventUuid(), b.getEventUuid());
        }
        finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            TestTransaction.start();
        }
    }
	
	@Test
    public void failureAfterOutboxWriteRollsBackClinicalSaveWithoutOuterTransaction() {
        PatientSyncService realService = sync();
        PatientSyncService failingService = mock(PatientSyncService.class);
        when(failingService.patientExists(any())).thenAnswer(call -> realService.patientExists(call.getArgument(0)));
        when(failingService.recordCreatedPatient(any())).thenAnswer(call -> {
            realService.recordCreatedPatient(call.getArgument(0));
            throw new org.openmrs.api.APIException("Error local simulado después de insertar el evento pendiente");
        });
        advice.setService(failingService);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        try {
            Patient patient = newPatient();
            assertThrows(org.openmrs.api.APIException.class, () -> Context.getPatientService().savePatient(patient));
            assertNull(Context.getPatientService().getPatientByUuid(patient.getUuid()));
            assertNull(realService.getByPatientUuid(patient.getUuid()));
        }
        finally {
            advice.setService(null);
            TestTransaction.start();
        }
    }
	
	@Autowired
	private SessionFactory sessionFactory;
	
	@BeforeEach
	public void prepareCapture() throws Exception {
		// Crea las tablas con el mismo archivo Liquibase que usa el módulo al instalarse.
		Connection connection = getConnection();
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(connection))
		        .update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("synchronizationmr.nodeLabel", "POSTA-01"));
		advice = new PatientCreationAdvice();
		Context.addAdvice(PatientService.class, advice);
	}
	
	@AfterEach
	public void removeAdvice() {
		Context.removeAdvice(PatientService.class, advice);
	}
	
	private Patient newPatient() {
		Patient patient = new Patient();
		patient.setGender("F");
		patient.addName(new PersonName("Paciente", null, "Prueba"));
		patient.addIdentifier(new PatientIdentifier("DEMO-" + UUID.randomUUID(), Context.getPatientService()
		        .getPatientIdentifierType(2), Context.getLocationService().getLocation(1)));
		return patient;
	}
	
	private PatientSyncService sync() {
		return Context.getService(PatientSyncService.class);
	}
	
	@Test
	public void clinicalSaveCreatesPendingIdentityAndRepeatedSaveDoesNotDuplicate() {
		Patient patient = Context.getPatientService().savePatient(newPatient());
		PatientSyncRecord first = sync().getByPatientUuid(patient.getUuid());
		assertNotNull(first);
		assertEquals("PENDING", first.getState());
		assertEquals("POSTA-01 / PACIENTE / " + first.getSequence(), first.getDisplayIdentifier());
		Context.getPatientService().savePatient(patient);
		PatientSyncRecord repeated = sync().getByPatientUuid(patient.getUuid());
		assertEquals(first.getSequence(), repeated.getSequence());
		assertEquals(first.getEventUuid(), repeated.getEventUuid());
		// Repetir el registro interno tampoco debe duplicar la identidad ni el evento.
		assertEquals(first.getEventUuid(), sync().recordCreatedPatient(patient).getEventUuid());
	}
	
	@Test
	public void successivePatientsShareOriginAndHaveDifferentSequences() {
		Patient first = Context.getPatientService().savePatient(newPatient());
		Patient second = Context.getPatientService().savePatient(newPatient());
		PatientSyncRecord a = sync().getByPatientUuid(first.getUuid());
		PatientSyncRecord b = sync().getByPatientUuid(second.getUuid());
		assertEquals(a.getOriginNodeUuid(), b.getOriginNodeUuid());
		assertEquals(a.getSequence() + 1, b.getSequence());
	}
	
	@Test
	public void changingVisibleLabelDoesNotChangeIdentity() {
		Patient patient = Context.getPatientService().savePatient(newPatient());
		PatientSyncRecord before = sync().getByPatientUuid(patient.getUuid());
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty("synchronizationmr.nodeLabel", "POSTA-NAPO"));
		PatientSyncRecord after = sync().getByPatientUuid(patient.getUuid());
		assertEquals(before.getOriginNodeUuid(), after.getOriginNodeUuid());
		assertEquals(before.getSequence(), after.getSequence());
		assertEquals("POSTA-NAPO", after.getNodeLabel());
	}
	
	@Test
	public void rollbackRemovesPatientIdentityEventAndCounterIncrement() {
		Patient patient = Context.getPatientService().savePatient(newPatient());
		String uuid = patient.getUuid();
		long allocated = sync().getByPatientUuid(uuid).getSequence();
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertNull(Context.getPatientService().getPatientByUuid(uuid));
		assertNull(sync().getByPatientUuid(uuid));
		Patient next = Context.getPatientService().savePatient(newPatient());
		assertEquals(allocated, sync().getByPatientUuid(next.getUuid()).getSequence());
	}
	
	@Test
	public void committedRecordSurvivesANewTransaction() {
		Patient patient = Context.getPatientService().savePatient(newPatient());
		String uuid = patient.getUuid();
		String eventUuid = sync().getByPatientUuid(uuid).getEventUuid();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		sessionFactory.getCurrentSession().clear();
		assertNotNull(Context.getPatientService().getPatientByUuid(uuid));
		assertEquals(eventUuid, sync().getByPatientUuid(uuid).getEventUuid());
	}
}

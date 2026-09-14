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

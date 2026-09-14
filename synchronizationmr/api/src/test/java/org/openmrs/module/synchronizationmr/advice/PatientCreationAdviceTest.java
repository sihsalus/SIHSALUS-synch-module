/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.advice;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.PatientService;
import org.openmrs.module.synchronizationmr.api.PatientSyncService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PatientCreationAdviceTest {
	
	private final PatientSyncService service = mock(PatientSyncService.class);
	
	private final PatientCreationAdvice advice = new PatientCreationAdvice();
	
	@BeforeEach
	public void setup() {
		advice.setService(service);
		TransactionSynchronizationManager.setActualTransactionActive(true);
	}
	
	@AfterEach
	public void cleanup() {
		TransactionSynchronizationManager.clear();
	}
	
	private MethodInvocation saving(Patient patient) throws Throwable {
		MethodInvocation call = mock(MethodInvocation.class);
		when(call.getMethod()).thenReturn(PatientService.class.getMethod("savePatient", Patient.class));
		when(call.getArguments()).thenReturn(new Object[] { patient });
		when(call.proceed()).thenReturn(patient);
		return call;
	}
	
	@Test
	public void recordsNewPatientAfterSuccessfulSave() throws Throwable {
		Patient patient = new Patient();
		MethodInvocation call = saving(patient);
		assertSame(patient, advice.invoke(call));
		verify(service).recordCreatedPatient(patient);
	}
	
	@Test
	public void recordsPersonConvertedToPatientWithExistingPersonId() throws Throwable {
		Patient patient = new Patient(17);
		when(service.patientExists(17)).thenReturn(false);
		advice.invoke(saving(patient));
		verify(service).recordCreatedPatient(patient);
	}
	
	@Test
	public void doesNotTreatPatientEditAsCreation() throws Throwable {
		Patient patient = new Patient(17);
		when(service.patientExists(17)).thenReturn(true);
		advice.invoke(saving(patient));
		verify(service, never()).recordCreatedPatient(any());
	}
	
	@Test
    public void doesNotRecordWhenClinicalSaveFails() throws Throwable {
        Patient patient = new Patient();
        MethodInvocation call = saving(patient);
        when(call.proceed()).thenThrow(new APIException("Error simulado al guardar al paciente"));
        assertThrows(APIException.class, () -> advice.invoke(call));
        verify(service, never()).recordCreatedPatient(any());
    }
	
	@Test
    public void captureFailurePropagatesToClinicalTransaction() throws Throwable {
        Patient patient = new Patient();
        when(service.recordCreatedPatient(patient)).thenThrow(new APIException("Error simulado al registrar el evento pendiente"));
        assertThrows(APIException.class, () -> advice.invoke(saving(patient)));
    }
	
	@Test
    public void startsTransactionWhenCoreAdviceHasNoTransactionYet() throws Throwable {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenAnswer(call -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            return status;
        });
        advice.setTransactionManager(manager);
        MethodInvocation call = saving(new Patient());
        advice.invoke(call);
        verify(manager).commit(status);
        verify(service).recordCreatedPatient(any());
    }
	
	@Test
    public void rejectsReadOnlyTransactionBeforeSave() throws Throwable {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        MethodInvocation call = saving(new Patient());
        assertThrows(APIException.class, () -> advice.invoke(call));
        verify(call, never()).proceed();
    }
	
	@Test
	public void leavesUnrelatedMethodsUntouched() throws Throwable {
		MethodInvocation call = mock(MethodInvocation.class);
		when(call.getMethod()).thenReturn(PatientService.class.getMethod("getPatient", Integer.class));
		Patient expected = new Patient(17);
		when(call.proceed()).thenReturn(expected);
		assertSame(expected, advice.invoke(call));
		verifyNoInteractions(service);
	}
}

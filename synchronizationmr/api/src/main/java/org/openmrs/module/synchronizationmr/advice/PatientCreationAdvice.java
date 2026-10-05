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

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.api.PatientSyncService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Captura CREATE y los cambios admitidos del paciente mediante PatientService y PersonService. */
public class PatientCreationAdvice implements MethodInterceptor {
	private static final ThreadLocal<Boolean> CAPTURING = new ThreadLocal<>();
	
	private PatientSyncService service;
	
	private PlatformTransactionManager transactionManager;
	
	public void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}
	
	public void setService(PatientSyncService service) {
		this.service = service;
	}
	
	private PatientSyncService service() {
		return service != null ? service : Context.getService(PatientSyncService.class);
	}
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		org.openmrs.Person person = person(invocation);
		if (person == null || Boolean.TRUE.equals(CAPTURING.get())) {
			return invocation.proceed();
		}
        if (org.openmrs.module.synchronizationmr.sync.IncomingPatientSave.isReceivingPerson(
                person)) { return invocation.proceed(); }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return capture(invocation);
        }
        // OpenMRS puede ejecutar este interceptor antes de abrir su transacción.
        // Envolvemos el guardado del paciente y del evento pendiente para confirmar
        // ambos juntos o deshacer ambos si ocurre un error local.
        PlatformTransactionManager manager = transactionManager != null ? transactionManager
                : Context.getRegisteredComponent("transactionManager", PlatformTransactionManager.class);
        return new TransactionTemplate(manager).execute(status -> {
            try {
                return capture(invocation);
            }
            catch (RuntimeException | Error failure) {
                throw failure;
            }
            catch (Throwable failure) {
                throw new APIException("No se pudo registrar la creación del paciente para sincronización", failure);
            }
        });
    }
	
	private Object capture(MethodInvocation invocation) throws Throwable {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
		        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new APIException("El interceptor de pacientes requiere una transacción de escritura de OpenMRS");
		}
		org.openmrs.Person person = person(invocation);
		boolean patientSave = "savePatient".equals(invocation.getMethod().getName());
		boolean exists = service().patientExists(person.getPersonId());
		if (!patientSave && !exists) { return invocation.proceed(); }
		service().lockPatientChanges();
		CAPTURING.set(true);
		try {
			Object result = invocation.proceed();
			if (patientSave && !exists) {
				service().recordCreatedPatient((Patient) result);
			} else if (!(invocation.getArguments()[0] instanceof org.openmrs.Person)) {
				service().recordPatientChildChanges(person.getPersonId());
			} else {
				Patient saved;
				if (result instanceof Patient) { saved = (Patient) result; }
				else {
					saved = new Patient((org.openmrs.Person) result);
					saved.setIdentifiers(Context.getPatientService().getPatient(person.getPersonId()).getIdentifiers());
				}
				service().recordPatientChanges(saved);
			}
			return result;
		} finally { CAPTURING.remove(); }
	}

	private org.openmrs.Person person(MethodInvocation invocation) {
		String method = invocation.getMethod().getName();
        if (invocation.getArguments() == null || invocation.getArguments().length == 0) { return null; }
		Object value = invocation.getArguments()[0];
		if (("savePatient".equals(method) || "savePerson".equals(method)) && value instanceof org.openmrs.Person) {
			return (org.openmrs.Person) value;
		}
		if (("savePersonName".equals(method) || "voidPersonName".equals(method) || "unvoidPersonName".equals(method))
		        && value instanceof org.openmrs.PersonName) { return ((org.openmrs.PersonName) value).getPerson(); }
		if (("savePersonAddress".equals(method) || "voidPersonAddress".equals(method) || "unvoidPersonAddress".equals(method))
		        && value instanceof org.openmrs.PersonAddress) { return ((org.openmrs.PersonAddress) value).getPerson(); }
		if (("savePatientIdentifier".equals(method) || "voidPatientIdentifier".equals(method) || "unvoidPatientIdentifier".equals(method))
		        && value instanceof org.openmrs.PatientIdentifier) { return ((org.openmrs.PatientIdentifier) value).getPatient(); }
		return null;
	}
}

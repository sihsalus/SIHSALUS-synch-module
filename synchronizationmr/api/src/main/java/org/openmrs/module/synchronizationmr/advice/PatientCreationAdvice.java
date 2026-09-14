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

/** Registra la creación de pacientes; no captura ediciones, fusiones ni pacientes anteriores. */
public class PatientCreationAdvice implements MethodInterceptor {
	
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
		if (!"savePatient".equals(invocation.getMethod().getName()) || invocation.getArguments().length != 1
		        || !(invocation.getArguments()[0] instanceof Patient)) {
			return invocation.proceed();
		}
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
		Patient patient = (Patient) invocation.getArguments()[0];
		boolean creation = !service().patientExists(patient.getPatientId());
		Object result = invocation.proceed();
		if (creation) {
			service().recordCreatedPatient((Patient) result);
		}
		return result;
	}
}

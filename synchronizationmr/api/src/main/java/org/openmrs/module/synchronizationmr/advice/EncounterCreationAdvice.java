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
import org.openmrs.Encounter;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.api.EncounterSyncService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Registra la creación de encuentros; no captura ediciones, fusiones ni encuentros anteriores. */
public class EncounterCreationAdvice implements MethodInterceptor {
	
	private EncounterSyncService service;
	
	private PlatformTransactionManager transactionManager;
	
	public void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}
	
	public void setService(EncounterSyncService service) {
		this.service = service;
	}
	
	private EncounterSyncService service() {
		return service != null ? service : Context.getService(EncounterSyncService.class);
	}
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		if (!"saveEncounter".equals(invocation.getMethod().getName()) || invocation.getArguments().length != 1
		        || !(invocation.getArguments()[0] instanceof Encounter)) {
			return invocation.proceed();
		}
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return capture(invocation);
        }
        // OpenMRS puede ejecutar este interceptor antes de abrir su transacción.
        // Envolvemos el guardado del encuentro y del evento pendiente para confirmar
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
                throw new APIException("No se pudo registrar la creación del encuentro para sincronización", failure);
            }
        });
    }
	
	private Object capture(MethodInvocation invocation) throws Throwable {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
		        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new APIException("El interceptor de encuentros requiere una transacción de escritura de OpenMRS");
		}
		Encounter encounter = (Encounter) invocation.getArguments()[0];
		boolean creation = !service().encounterExists(encounter.getEncounterId());
		Object result = invocation.proceed();
		if (creation) {
			service().recordCreatedEncounter((Encounter) result);
		}
		return result;
	}
}

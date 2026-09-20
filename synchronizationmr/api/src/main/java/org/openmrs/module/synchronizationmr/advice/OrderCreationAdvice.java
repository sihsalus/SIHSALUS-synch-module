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
import org.openmrs.Order;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.api.OrderSyncService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Registra la creación de órdenes; no captura ediciones, fusiones ni órdenes anteriores. */
public class OrderCreationAdvice implements MethodInterceptor {
	
	private OrderSyncService service;
	
	private PlatformTransactionManager transactionManager;
	
	public void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}
	
	public void setService(OrderSyncService service) {
		this.service = service;
	}
	
	private OrderSyncService service() {
		return service != null ? service : Context.getService(OrderSyncService.class);
	}
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {

        String method = invocation.getMethod().getName();
        boolean save = ("saveOrder".equals(method) || "saveRetrospectiveOrder".equals(method)) && invocation.getArguments().length==2;
        boolean discontinue = "discontinueOrder".equals(method) && invocation.getArguments().length==5;
        if ((!save && !discontinue) || !(invocation.getArguments()[0] instanceof Order)) return invocation.proceed();

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return capture(invocation);
        }
        // OpenMRS puede ejecutar este interceptor antes de abrir su transacción.
        // Envolvemos el guardado de la orden y del evento pendiente para confirmar
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
                throw new APIException("No se pudo registrar la creación de la orden para sincronización", failure);
            }
        });
    }
	
	private Object capture(MethodInvocation invocation) throws Throwable {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
		        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new APIException("El interceptor de órdenes requiere una transacción de escritura de OpenMRS");
		}
		Order order = (Order) invocation.getArguments()[0];
		if (org.openmrs.module.synchronizationmr.sync.IncomingOrderSave.isReceiving(order)) {
			return invocation.proceed();
		}
		boolean creation = "discontinueOrder".equals(invocation.getMethod().getName())
		        || !service().orderExists(order.getOrderId());
		Object result = invocation.proceed();
		if (creation) {
			service().recordCreatedOrder((Order) result);
		}
		return result;
	}
}

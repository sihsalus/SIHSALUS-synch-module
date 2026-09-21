package org.openmrs.module.synchronizationmr.api.impl;

import org.openmrs.Order;
import org.openmrs.api.APIException;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.synchronizationmr.api.OrderSyncService;
import org.openmrs.module.synchronizationmr.api.dao.OrderSyncDao;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class OrderSyncServiceImpl extends BaseOpenmrsService implements OrderSyncService {
	
	@Override
	public int prepareExistingOrders(int batchSize) {
		requireTransaction();
		limit(batchSize);
		
		int prepared = 0;
		while (prepared < batchSize) {
			if (Thread.currentThread().isInterrupted())
				throw new APIException("Preparacion interrumpida");
			java.util.List<Integer> ids = dao.lockAndFindPending(batchSize - prepared);
			if (ids.isEmpty()) {
				if (prepared == 0 && dao.countPending() > 0) {
					throw new APIException(
					        "Quedan ordenes pendientes: su orden anterior no esta preparada; revise referencias, anulaciones o ciclos");
				}
				break;
			}
			for (Integer id : ids) {
				if (Thread.currentThread().isInterrupted())
					throw new APIException("Preparacion interrumpida");
				Order order = org.openmrs.api.context.Context.getOrderService().getOrder(id);
				if (order == null || Boolean.TRUE.equals(order.getVoided()))
					throw new APIException("Orden inexistente o anulada");
				dao.capture(order);
				dao.requirePayload(id);
				prepared++;
			}
		}
		return prepared;
	}
	
	@Override
	public long countOrdersPendingPreparation() {
		return dao.countPending();
	}
	
	private void limit(int limit) {
		if (limit < 1 || limit > 100) {
			throw new APIException("El límite debe estar entre 1 y 100");
		}
	}
	
	@Override
	public java.util.List<String> getOrderOrigins(String after, int limit) {
		limit(limit);
		return dao.findOrderOrigins(
		    after == null ? "" : org.openmrs.module.synchronizationmr.sync.ServerId.requireValid(after), limit);
	}
	
	@Override
	public long getHighestOrderSequence(String origin) {
		return dao.findHighestOrderSequence(org.openmrs.module.synchronizationmr.sync.ServerId.requireValid(origin));
	}
	
	@Override
	public java.util.List<org.openmrs.module.synchronizationmr.sync.OrderSyncEvent> getOrderEventsAfter(String origin,
	        long after, int limit) {
		limit(limit);
		if (after < 0) {
			throw new APIException("La secuencia no puede ser negativa");
		}
		return dao.findOrderEventsAfter(org.openmrs.module.synchronizationmr.sync.ServerId.requireValid(origin), after,
		    limit);
	}
	
	private OrderSyncDao dao;
	
	public void setDao(OrderSyncDao dao) {
		this.dao = dao;
	}
	
	private void requireTransaction() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
		        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new APIException("La captura de la orden requiere una transacción de escritura");
		}
	}
	
	@Override
	public boolean orderExists(Integer id) {
		requireTransaction();
		return dao.exists(id);
	}
	
	@Override
	public void recordCreatedOrder(Order order) {
		requireTransaction();
		if (order == null || order.getOrderId() == null || order.getUuid() == null || order.getPatient() == null
		        || order.getPatient().getPatientId() == null) {
			throw new APIException("Se requiere una orden guardado con su paciente local");
		}
		dao.capture(order);
	}
}

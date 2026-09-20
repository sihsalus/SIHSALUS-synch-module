package org.openmrs.module.synchronizationmr.api;

import org.openmrs.Order;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Captura y consulta de eventos de órdenes para transporte JSON. */
public interface OrderSyncService extends OpenmrsService {
	
	@Authorized(value = { "Prepare Synchronization Records", "Get Orders", "Get Observations" }, requireAll = true)
	@Transactional
	int prepareExistingOrders(int limit);
	
	@Authorized("View Synchronization Records")
	@Transactional(readOnly = true)
	long countOrdersPendingPreparation();
	
	@Authorized("View Synchronization Records")
	@Transactional(readOnly = true)
	java.util.List<String> getOrderOrigins(String afterOrigin, int limit);
	
	@Authorized("View Synchronization Records")
	@Transactional(readOnly = true)
	long getHighestOrderSequence(String origin);
	
	@Authorized(value = { "View Synchronization Records", "Get Orders", "Get Observations" }, requireAll = true)
	@Transactional(readOnly = true)
	java.util.List<org.openmrs.module.synchronizationmr.sync.OrderSyncEvent> getOrderEventsAfter(String origin, long after,
	        int limit);
	
	@Transactional(propagation = Propagation.MANDATORY)
	boolean orderExists(Integer orderId);
	
	@Transactional(propagation = Propagation.MANDATORY)
	void recordCreatedOrder(Order order);
}

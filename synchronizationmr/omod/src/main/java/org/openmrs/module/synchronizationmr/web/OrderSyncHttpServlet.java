package org.openmrs.module.synchronizationmr.web;

import java.util.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.api.*;
import org.openmrs.module.synchronizationmr.sync.*;

/** Same authenticated HTTPS protocol as patients, with independent order sequences. */
public class OrderSyncHttpServlet extends PatientSyncHttpServlet {
	
	protected OrderSyncService orders() {
		return Context.getService(OrderSyncService.class);
	}
	
	protected OrderReceiveService orderReceiver() {
		return Context.getService(OrderReceiveService.class);
	}
	
	@Override
	protected void authorizeReceive(PatientSyncPeerSession peer, String origin) {
		peer.authorizeOrderReceive(origin);
	}
	
	@Override
	protected String entityType() {
		return "ORDER";
	}
	
	@Override
	protected String incomingOrigin(String json) {
		return new OrderIncomingEvent(json).origin;
	}
	
	@Override
	protected long receiveEvent(String json) {
		return orderReceiver().receiveOrder(json);
	}
	
	@Override
	protected List<String> origins(String after, int limit) {
		return orders().getOrderOrigins(after, limit);
	}
	
	@Override
	protected long highest(String origin) {
		return orders().getHighestOrderSequence(origin);
	}
	
	@Override
	protected long confirmed(String origin) {
		return orderReceiver().getConfirmedOrderSequence(origin);
	}
	
	@Override protected List<String> payloads(String origin, long after, int limit) {
        List<String> result = new ArrayList<>();
        for (OrderSyncEvent event : orders().getOrderEventsAfter(origin, after, limit)) { result.add(event.getPayloadJson()); }
        return result;
    }
	
	@Override
	protected void enrichStatus(com.fasterxml.jackson.databind.node.ObjectNode result) {
		result.put("pendingOrderLinksTotal", orderReceiver().countPendingOrderLinks());
	}
}

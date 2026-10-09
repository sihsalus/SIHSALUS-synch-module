package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.openmrs.Order;
import static org.openmrs.module.synchronizationmr.sync.EventJson.*;

/** Instantánea acotada del cumplimiento; no modifica la prescripción ni sus resultados. */
public final class OrderFulfillment {

	private static final ObjectMapper JSON = new ObjectMapper();

	private OrderFulfillment() {
	}

	public static ObjectNode snapshot(Order order) {
		ObjectNode data = JSON.createObjectNode();
		data.put("fulfillerStatus", order.getFulfillerStatus() == null ? null : order.getFulfillerStatus().name());
		data.put("fulfillerComment", order.getFulfillerComment());
		data.put("accessionNumber", order.getAccessionNumber());
		validate(data);
		return data;
	}

	public static void validate(JsonNode data) {
		if (data == null || !data.isObject())
			throw invalid();
		for (String field : new String[] { "fulfillerStatus", "fulfillerComment", "accessionNumber" }) {
			if (!data.has(field))
				throw invalid();
			String value = text(data, field, false);
			if (value != null && value.length() > ("fulfillerComment".equals(field) ? 1024 : 255))
				throw invalid();
		}
		String status = text(data, "fulfillerStatus", false);
		try {
			if (status != null)
				Order.FulfillerStatus.valueOf(status);
		}
		catch (IllegalArgumentException failure) {
			throw invalid();
		}
	}

	public static String create(Order order, String origin, long sequence, Instant time) {
		ObjectNode root = JSON.createObjectNode();
		root.put("schemaVersion", 2).put("entityType", "ORDER").put("operation", "UPDATE_FULFILLMENT");
		root.put("originServerId", origin).put("entitySequence", sequence);
		root.put("eventUuid", UUID.randomUUID().toString()).put("occurredAt", time.toString());
		ObjectNode data = snapshot(order);
		data.put("orderUuid", order.getUuid()).put("patientUuid", order.getPatient().getUuid());
		data.put("encounterUuid", order.getEncounter().getUuid());
		root.set("payload", data);
		return root.toString();
	}

	/** Desempate estable para que todos los nodos elijan la misma instantánea. */
	public static int compare(OrderIncomingEvent left, OrderIncomingEvent right) {
		int c = left.occurredAt.compareTo(right.occurredAt);
		if (c == 0)
			c = left.origin.compareTo(right.origin);
		if (c == 0)
			c = Long.compare(left.sequence, right.sequence);
		if (c == 0)
			c = left.eventUuid.compareTo(right.eventUuid);
		return c;
	}

	public static void apply(JsonNode data, Order order) {
		validate(data);
		String status = text(data, "fulfillerStatus", false);
		order.setFulfillerStatus(status == null ? null : Order.FulfillerStatus.valueOf(status));
		order.setFulfillerComment(text(data, "fulfillerComment", false));
		order.setAccessionNumber(text(data, "accessionNumber", false));
	}
}

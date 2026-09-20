package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Date;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.springframework.stereotype.Component;
import static org.openmrs.module.synchronizationmr.sync.OrderPayload.*;

@Component("synchronizationmr.OrderCreationPayloadSerializer")
public class OrderCreationPayloadSerializer {
	
	public String serialize(Order order, String origin, long sequence, String eventUuid, Date created) {
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode event = mapper.createObjectNode();
		event.put("schemaVersion", 1);
		event.put("entityType", "ORDER");
		event.put("operation", "CREATE");
		event.put("originServerId", ServerId.requireValid(origin));
		event.put("entitySequence", sequence);
		event.put("eventUuid", eventUuid);
		event.put("occurredAt", created.toInstant().toString());
		ObjectNode data = event.putObject("payload");
		data.put("previousOrderDateStopped",
		    order.getPreviousOrder() == null || order.getPreviousOrder().getDateStopped() == null ? null : java.time.Instant
		            .ofEpochMilli(order.getPreviousOrder().getDateStopped().getTime()).toString());
		String kind = order instanceof DrugOrder ? "DRUG" : order instanceof TestOrder ? "TEST" : "ORDER";
		// An unknown extension must provide its own contract instead of losing its additional fields.
		Class<?> concrete = org.hibernate.Hibernate.getClass(order);
		if (concrete != Order.class && concrete != DrugOrder.class && concrete != TestOrder.class) {
			throw new APIException("Unsupported order subclass");
		}
		data.put("kind", kind);
		ref(data, "orderUuid", order);
		ref(data, "patientUuid", order.getPatient());
		ref(data, "encounterUuid", order.getEncounter());
		data.put("sourceOrderNumber", order.getOrderNumber());
		data.put("dateStopped", order.getDateStopped() == null ? null : order.getDateStopped().toInstant().toString());
		write(data, order, COMMON);
		ref(data, "orderTypeUuid", order.getOrderType());
		ref(data, "conceptUuid", order.getConcept());
		ref(data, "ordererUuid", order.getOrderer());
		ref(data, "orderReasonUuid", order.getOrderReason());
		ref(data, "careSettingUuid", order.getCareSetting());
		ref(data, "previousOrderUuid", order.getPreviousOrder());
		ref(data, "orderGroupUuid", order.getOrderGroup());
		if (order instanceof DrugOrder) {
			DrugOrder d = (DrugOrder) order;
			write(data, d, DRUG);
			ref(data, "doseUnitsUuid", d.getDoseUnits());
			ref(data, "frequencyUuid", d.getFrequency());
			ref(data, "quantityUnitsUuid", d.getQuantityUnits());
			ref(data, "drugUuid", d.getDrug());
			ref(data, "durationUnitsUuid", d.getDurationUnits());
			ref(data, "routeUuid", d.getRoute());
			data.put("dosingType", d.getDosingType() == null ? null : d.getDosingType().getName());
		} else if (order instanceof TestOrder) {
			TestOrder t = (TestOrder) order;
			write(data, t, TEST);
			ref(data, "specimenSourceUuid", t.getSpecimenSource());
			ref(data, "frequencyUuid", t.getFrequency());
		}
		try {
			return mapper.writeValueAsString(event);
		}
		catch (java.io.IOException e) {
			throw new APIException("Cannot serialize order", e);
		}
	}
}

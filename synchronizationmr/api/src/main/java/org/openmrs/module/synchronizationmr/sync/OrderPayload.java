package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Date;
import org.openmrs.*;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import static org.openmrs.module.synchronizationmr.sync.EventJson.*;

/** Explicit clinical fields only; never serializes a Hibernate graph or remote Java class. */
final class OrderPayload {
	
	static final String COMMON = "instructions,accessionNumber,orderReasonNonCoded,commentToFulfiller,fulfillerComment,voidReason,urgency,action,fulfillerStatus,sortWeight,dateActivated,autoExpireDate,scheduledDate,voided";
	
	static final String DRUG = "dose,asNeeded,quantity,asNeededCondition,numRefills,dosingInstructions,duration,brandName,dispenseAsWritten,drugNonCoded";
	
	static final String TEST = "laterality,clinicalHistory,numberOfRepeats";
	
	static final String REFS = "orderTypeUuid,conceptUuid,ordererUuid,orderReasonUuid,careSettingUuid,previousOrderUuid,orderGroupUuid";
	
	static final String DRUG_REFS = "doseUnitsUuid,frequencyUuid,quantityUnitsUuid,drugUuid,durationUnitsUuid,routeUuid,dosingType";
	
	static final String TEST_REFS = "specimenSourceUuid,frequencyUuid";
	
	static void write(ObjectNode node, Object bean, String names) {
		BeanWrapper wrapper = new BeanWrapperImpl(bean);
		for (String name : names.split(",")) {
			Object value = wrapper.getPropertyValue(name);
			if (value instanceof Date)
				node.put(name, java.time.Instant.ofEpochMilli(((Date) value).getTime()).toString());
			else if (value instanceof Enum)
				node.put(name, ((Enum<?>) value).name());
			else
				node.putPOJO(name, value);
		}
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static void read(JsonNode node, Object bean, String names) {
		BeanWrapper wrapper = new BeanWrapperImpl(bean);
		for (String name : names.split(",")) {
			JsonNode value = node.get(name);
			if (value == null)
				throw invalid();
			Object converted = null;
			Class<?> type = wrapper.getPropertyType(name);
			if (!value.isNull()) {
				if (type == String.class)
					converted = text(node, name, false);
				else if (type == Date.class)
					converted = instant(text(node, name, true));
				else if (type.isEnum()) {
					try {
						converted = Enum.valueOf((Class) type, text(node, name, true));
					}
					catch (IllegalArgumentException e) {
						throw invalid();
					}
				} else if (type == Boolean.class || type == boolean.class) {
					if (!value.isBoolean())
						throw invalid();
					converted = value.booleanValue();
				} else if (type == Integer.class || type == int.class) {
					if (!value.isIntegralNumber() || !value.canConvertToInt())
						throw invalid();
					converted = value.intValue();
				} else if (type == Double.class || type == double.class) {
					if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
						throw invalid();
					converted = value.doubleValue();
				} else
					throw invalid();
			}
			wrapper.setPropertyValue(name, converted);
		}
	}
	
	static void ref(ObjectNode node, String field, OpenmrsObject object) {
		node.put(field, object == null ? null : reference(object.getUuid()));
	}
}

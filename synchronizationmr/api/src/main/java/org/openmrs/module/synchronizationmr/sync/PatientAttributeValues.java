package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;

/** Explicit attribute formats; never load classes specified by a received message. */
final class PatientAttributeValues {
	
	private PatientAttributeValues() {
	}
	
	static ObjectNode serialize(PersonAttribute attribute, ObjectMapper mapper) {
		PersonAttributeType type = attribute.getAttributeType();
		if (type == null || type.getUuid() == null || Boolean.TRUE.equals(type.getRetired())) {
			throw invalid();
		}
		String format = type.getFormat();
		String value = attribute.getValue();
		String reference = null;
		if (isReference(format)) {
			int id;
			try {
				id = Integer.parseInt(value);
			}
			catch (RuntimeException e) {
				throw invalid();
			}
			OpenmrsObject target = "org.openmrs.Concept".equals(format) ? Context.getConceptService().getConcept(id)
			        : Context.getLocationService().getLocation(id);
			if (target == null || retired(target)) {
				throw invalid();
			}
			reference = target.getUuid();
			if (reference == null || reference.trim().isEmpty()) {
				throw invalid();
			}
			value = null;
		} else {
			validateSimple(format, value);
		}
		if (attribute.getUuid() == null || attribute.getUuid().trim().isEmpty()) {
			throw invalid();
		}
		ObjectNode item = mapper.createObjectNode();
		item.put("uuid", attribute.getUuid());
		item.put("attributeTypeUuid", type.getUuid());
		item.put("format", format);
		item.put("value", value);
		item.put("valueReferenceUuid", reference);
		return item;
	}
	
	static PersonAttribute receive(String uuid, String typeUuid, String format, String value, String reference) {
		PersonAttributeType type = Context.getPersonService().getPersonAttributeTypeByUuid(typeUuid);
		if (type == null || Boolean.TRUE.equals(type.getRetired()) || !format.equals(type.getFormat())) {
			throw invalid();
		}
		if (isReference(format)) {
			if (value != null || reference == null || reference.trim().isEmpty() || reference.length() > 38) {
				throw invalid();
			}
			OpenmrsObject target = "org.openmrs.Concept".equals(format) ? Context.getConceptService().getConceptByUuid(
			    reference) : Context.getLocationService().getLocationByUuid(reference);
			if (target == null || retired(target)) {
				throw invalid();
			}
			value = target.getId().toString();
		} else {
			if (reference != null) {
				throw invalid();
			}
			validateSimple(format, value);
		}
		PersonAttribute attribute = new PersonAttribute(type, value);
		attribute.setUuid(uuid);
		return attribute;
	}
	
	private static boolean retired(OpenmrsObject target) {
		return Boolean.TRUE.equals(target instanceof Concept ? ((Concept) target).getRetired() : ((Location) target)
		        .getRetired());
	}
	
	private static boolean isReference(String format) {
		return "org.openmrs.Concept".equals(format) || "org.openmrs.Location".equals(format);
	}
	
	private static void validateSimple(String format, String value) {
		if (value == null || value.trim().isEmpty() || value.length() > 4096) {
			throw invalid();
		}
		try {
			if ("java.lang.String".equals(format)) {
				return;
			}
			if ("java.lang.Boolean".equals(format)) {
				if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
					throw invalid();
				}
			} else if ("java.lang.Integer".equals(format)) {
				Integer.valueOf(value);
			} else if ("java.lang.Long".equals(format)) {
				Long.valueOf(value);
			} else if ("java.lang.Float".equals(format)) {
				if (!Float.isFinite(Float.parseFloat(value))) {
					throw invalid();
				}
			} else if ("java.lang.Double".equals(format)) {
				if (!Double.isFinite(Double.parseDouble(value))) {
					throw invalid();
				}
			} else {
				throw invalid();
			}
		}
		catch (NumberFormatException e) {
			throw invalid();
		}
	}
	
	private static APIException invalid() {
		return new APIException("Atributo de paciente no admitido: revise tipo, formato, valor y referencias de catálogo");
	}
}

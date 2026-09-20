package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.time.*;
import org.openmrs.api.APIException;

/** Validación estructural sin acceso a datos ni deserialización de clases arbitrarias. */
final class EventJson {
	
	public static String uuid(String value) {
		if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
			throw invalid();
		}
		return value;
	}
	
	// Los UUID nativos son referencias opacas: algunos catálogos OpenMRS no usan formato RFC.
	// El identificador del evento se valida con uuid(); el origen usa ServerId.
	static String reference(String value) {
		if (value == null || value.trim().isEmpty() || value.length() > 38) {
			throw invalid();
		}
		return value;
	}
	
	static String unique(JsonNode item, Set<String> seen) {
		String id = reference(text(item, "uuid", true));
		if (!seen.add(id)) {
			throw invalid();
		}
		return id;
	}
	
	static void fields(JsonNode node, String allowed) {
        if (node == null || !node.isObject()) { throw invalid(); }
        Set<String> names = new HashSet<>(Arrays.asList(allowed.split(",")));
        Iterator<String> keys = node.fieldNames();
        while (keys.hasNext()) { if (!names.contains(keys.next())) { throw invalid(); } }
    }
	
	static JsonNode array(JsonNode node, String key, boolean nonempty) {
		JsonNode value = node.get(key);
		if (value == null || !value.isArray() || value.size() > 100 || (nonempty && value.size() == 0)) {
			throw invalid();
		}
		return value;
	}
	
	static String text(JsonNode node, String key, boolean required) {
		JsonNode value = node.get(key);
		if (value == null || value.isNull()) {
			if (required) {
				throw invalid();
			}
			return null;
		}
		if (!value.isTextual() || (required && value.asText().trim().isEmpty())) {
			throw invalid();
		}
		return value.asText();
	}
	
	static boolean flag(JsonNode node, String key) {
		JsonNode value = node.get(key);
		if (value == null || !value.isBoolean()) {
			throw invalid();
		}
		return value.booleanValue();
	}
	
	static Date instant(String value) {
		try {
			return value == null ? null : Date.from(Instant.parse(value));
		}
		catch (Exception e) {
			throw invalid();
		}
	}
	
	static APIException invalid() {
		return new APIException("El evento de sincronización no cumple el contrato admitido");
	}
	
	static APIException dependency(String name) {
		return new APIException("Falta una referencia activa en el destino: " + name);
	}
	
	static APIException conflict() {
		return new APIException("Un UUID recibido ya pertenece a un registro local; se requiere revisión");
	}
}

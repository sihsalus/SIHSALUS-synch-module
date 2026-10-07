package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.openmrs.Encounter;
import org.openmrs.api.APIException;

/**
 * Copia inmutable de los datos representados por el contrato CREATE del encuentro. Permite comparar
 * estados sin conservar referencias a entidades de Hibernate. No captura eventos UPDATE ni aplica
 * correcciones; la visita y las Ã³rdenes mantienen sus propios flujos. Los archivos complejos y el
 * contenido de diagnÃ³sticos/condiciones no estÃ¡n representados.
 */
public final class EncounterSnapshot {

	private final ObjectNode data;

	private EncounterSnapshot(ObjectNode data) {
		this.data = data;
		sortObjects((ArrayNode) data.get("encounterProviders"), false);
		sortObjects((ArrayNode) data.get("obs"), true);
		sortReferences((ArrayNode) data.get("orderUuids"));
		sortReferences((ArrayNode) data.get("unsupportedContent"));
	}

	public static EncounterSnapshot capture(Encounter encounter) {
		if (encounter == null || encounter.getUuid() == null || encounter.getUuid().trim().isEmpty()) {
			throw new APIException("Se requiere un encuentro con UUID para comparar modificaciones");
		}
		return new EncounterSnapshot(new EncounterCreationPayloadSerializer().snapshot(encounter));
	}

	/**
	 * Campos principales que difieren, incluidas incorporaciones, correcciones y anulaciones de
	 * observaciones. Un cambio detectado no implica que su sincronizaciÃ³n estÃ© implementada.
	 */
	public Set<String> changedFields(EncounterSnapshot after) {
        if (after == null || !data.get("encounterUuid").equals(after.data.get("encounterUuid"))
                || !data.get("patientUuid").equals(after.data.get("patientUuid"))) {
            throw new APIException("No se pueden comparar encuentros con distinta identidad o paciente");
        }
        Set<String> changed = new LinkedHashSet<>();
        Iterator<String> fields = data.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!data.get(field).equals(after.data.get(field))) {
                changed.add(field);
            }
        }
        return Collections.unmodifiableSet(changed);
    }

	/** Contiene informaciÃ³n clÃ­nica; devolver una copia no autoriza escribirla en los logs. */
	public ObjectNode toJson() {
		return data.deepCopy();
	}

	private static void sortObjects(ArrayNode items, boolean observations) {
        Map<String, JsonNode> sorted = new TreeMap<>();
        for (JsonNode item : items) {
            String uuid = item.path("uuid").asText();
            if (sorted.put(uuid, item) != null) {
                throw new APIException("La instantÃ¡nea del encuentro contiene UUID repetidos");
            }
            if (observations) {
                // La pertenencia a un grupo se conserva; solo se normaliza el orden de recorrido.
                sortObjects((ArrayNode) item.get("groupMembers"), true);
            }
        }
        items.removeAll();
        for (JsonNode item : sorted.values()) {
            items.add(item);
        }
    }

	private static void sortReferences(ArrayNode items) {
        Map<String, JsonNode> sorted = new TreeMap<>();
        for (JsonNode item : items) {
            if (sorted.put(item.asText(), item) != null) {
                throw new APIException("La instantÃ¡nea del encuentro contiene referencias repetidas");
            }
        }
        items.removeAll();
        for (JsonNode item : sorted.values()) {
            items.add(item);
        }
    }
}

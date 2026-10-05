package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.openmrs.Patient;
import org.openmrs.api.APIException;

/**
 * Copia inmutable de los campos activos del paciente admitidos por el contrato de creación. Detecta
 * cambios; no guarda eventos, aplica cambios remotos ni resuelve conflictos. Capture el estado
 * inicial antes de editar: otra referencia a una entidad de Hibernate no es una copia.
 */
public final class PatientSnapshot {
	
	private final ObjectNode data;
	
	private PatientSnapshot(ObjectNode data) {
        this.data = data;
        // El orden de recorrido del conjunto no es un cambio clínico. El UUID identifica cada elemento;
        // preferred y todos sus demás valores también participan en la comparación.
        for (String field : Arrays.asList("names", "addresses", "identifiers", "attributes")) {
            ArrayNode items = (ArrayNode) data.get(field);
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (JsonNode item : items) {
                String uuid = item.path("uuid").asText();
                if (sorted.put(uuid, item) != null) {
                    throw new APIException("La instantánea contiene UUID repetidos en " + field);
                }
            }
            items.removeAll();
            for (JsonNode item : sorted.values()) {
                items.add(item);
            }
        }
    }
	
	public static PatientSnapshot capture(Patient patient) {
		if (patient == null || patient.getUuid() == null || patient.getUuid().trim().isEmpty()) {
			throw new APIException("Se requiere un paciente con UUID para comparar modificaciones");
		}
		if (Boolean.TRUE.equals(patient.getVoided())) {
			throw new APIException("La anulación del paciente requiere un flujo distinto de modificación");
		}
		return new PatientSnapshot(new PatientCreationPayloadSerializer().snapshot(patient));
	}
	
	/** Campos principales que difieren; las colecciones incluyen altas, modificaciones y bajas. */
	public Set<String> changedFields(PatientSnapshot after) {
        if (after == null || !data.get("patientUuid").equals(after.data.get("patientUuid"))) {
            throw new APIException("No se pueden comparar modificaciones de pacientes con distinta identidad");
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
	
	/**
	 * Copia independiente y defensiva. Contiene datos personales; no debe escribirse en los
	 * registros de ejecución.
	 */
	public ObjectNode toJson() {
		return data.deepCopy();
	}
}

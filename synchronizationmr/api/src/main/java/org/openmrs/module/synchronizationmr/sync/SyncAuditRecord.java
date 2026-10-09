package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Metadatos de un intento; nunca conserva el contenido clínico ni mensajes de excepción. */
public final class SyncAuditRecord {

	public final String source, destination, entityType, action, eventOrigin, eventUuid;

	public final Long sequence;

	public SyncAuditRecord(String source, String destination, String entityType, String action, JsonNode event) {
		this.source = ServerId.requireValid(source);
		this.destination = ServerId.requireValid(destination);
		if (!java.util.Arrays.asList("PATIENT", "ENCOUNTER", "ORDER").contains(entityType)
		        || !java.util.Arrays.asList("SEND", "RECEIVE", "SERVE_EVENT", "QUERY_NODE", "QUERY_STATUS", "QUERY_ORIGINS",
		            "QUERY_EVENTS", "REQUEST").contains(action)) {
			throw new IllegalArgumentException("Tipo de auditoría inválido");
		}
		this.entityType = entityType;
		this.action = action;
		String origin = null, uuid = null;
		Long seq = null;
		if (event != null && entityType.equals(event.path("entityType").asText())) {
			String candidate = event.path("eventUuid").asText();
			try {
				if (UUID.fromString(candidate).toString().equals(candidate)) {
					String candidateOrigin = event.path("originServerId").asText();
					JsonNode number = event.path("entitySequence");
					if (ServerId.isValid(candidateOrigin) && number.isIntegralNumber() && number.canConvertToLong()
					        && number.longValue() > 0) {
						uuid = candidate;
						origin = candidateOrigin;
						seq = number.longValue();
					}
				}
			}
			catch (IllegalArgumentException invalid) {
				// Una solicitud inválida se audita sin atribuirle una identidad de evento válida.
			}
		}
		eventOrigin = origin;
		eventUuid = uuid;
		sequence = seq;
	}
}

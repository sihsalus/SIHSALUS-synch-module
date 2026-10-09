package org.openmrs.module.synchronizationmr.sync;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.api.SyncAuditService;

/** Rodea operaciones sin envolver la red en una transacción de la base clínica. */
public class SyncAudit {

	private final SyncAuditService service;

	public SyncAudit(SyncAuditService service) {
		this.service = service;
	}

	public static SyncAudit local() {
		return new SyncAudit(Context.getService(SyncAuditService.class));
	}

	public static JsonNode metadata(String payload) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
		}
		catch (IOException invalid) {
			return null;
		}
	}

	public interface Operation<T> {

		T execute() throws IOException;
	}

	public String begin(String source, String destination, String type, String action, JsonNode event) {
		return service.begin(new SyncAuditRecord(source, destination, type, action, event));
	}

	public void finish(String id, String result, String code) {
		service.finish(id, result, code);
	}

	public void failure(String id, String action, Exception failure) {
		String code = failure instanceof IOException ? "COMMUNICATION"
		        : failure instanceof IllegalArgumentException ? "INVALID" : failure instanceof APIException ? "REJECTED"
		                : "INTERNAL";
		try {
			finish(id, "SEND".equals(action) ? "UNCONFIRMED" : "FAILED", code);
		}
		catch (RuntimeException auditFailure) {
			// La fila inicial permanece en curso; no sustituir la excepción original ni revelar datos.
			org.apache.commons.logging.LogFactory.getLog(SyncAudit.class).error("No se pudo cerrar el intento de auditoría");
		}
	}

	public <T> T run(String source, String destination, String type, String action, JsonNode event,
            String success, Operation<T> operation) throws IOException {
        String id = begin(source,destination,type,action,event);
        T result;
        try { result = operation.execute(); }
        catch (IOException | RuntimeException failure) { failure(id,action,failure); throw failure; }
        // Si falla el cierre, no declarar fallida una operación que ya terminó correctamente.
        finish(id,success,"OK");
        return result;
    }
}

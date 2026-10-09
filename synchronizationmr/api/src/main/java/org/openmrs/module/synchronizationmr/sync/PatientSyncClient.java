package org.openmrs.module.synchronizationmr.sync;

import java.io.IOException;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import org.openmrs.module.synchronizationmr.api.LocalNodeService;
import org.openmrs.module.synchronizationmr.api.PatientReceiveService;
import org.openmrs.module.synchronizationmr.api.PatientSyncService;
import org.openmrs.api.context.Context;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Un ciclo explícito de una posta. El llamador aporta un contexto OpenMRS autenticado. */
public class PatientSyncClient {
	
	private final LocalNodeService node;
	
	private final PatientSyncService records;
	
	private final PatientReceiveService receiver;
	
	private final PatientRemoteTransport remote;
	
	private final String masterServerId;
	
	private SyncAudit audit;

	public PatientSyncClient(LocalNodeService node, PatientSyncService records, PatientReceiveService receiver,
	    PatientRemoteTransport remote, String masterServerId, SyncAudit audit) {
		this(node, records, receiver, remote, masterServerId);
		this.audit = java.util.Objects.requireNonNull(audit);
	}

	private SyncAudit audit() {
		if (audit == null) {
			audit = SyncAudit.local();
		}
		return audit;
	}

	private JsonNode query(String local, String query, String action) throws IOException {
        return audit().run(local,masterServerId,"PATIENT",action,null,"SUCCEEDED",() -> remote.get(query));
    }

	/**
	 * Construye el cliente con los servicios transaccionales de OpenMRS. Las credenciales remotas
	 * se proporcionan en memoria, sin escribirlas en propiedades globales.
	 */
	public static PatientSyncClient forLocalPosta(String endpoint, String masterServerId, String username, String password)
	        throws IOException {
		if (!"POSTA".equals(Context.getAdministrationService().getGlobalProperty("synchronizationmr.nodeRole"))) {
			throw new IllegalStateException("El cliente debe ejecutarse en una instalación configurada como POSTA");
		}
		return new PatientSyncClient(Context.getService(LocalNodeService.class),
		        Context.getService(PatientSyncService.class), Context.getService(PatientReceiveService.class),
		        new PatientHttpsTransport(endpoint, username, password), masterServerId);
	}
	
	public PatientSyncClient(LocalNodeService node, PatientSyncService records, PatientReceiveService receiver,
	    PatientRemoteTransport remote, String masterServerId) {
		this.node = node;
		this.records = records;
		this.receiver = receiver;
		this.remote = remote;
		this.masterServerId = ServerId.requireValid(masterServerId);
	}
	
	/** Devuelve envíos y recepciones confirmados en este ciclo; no crea un temporizador. */
	public int[] synchronizeOnce() throws IOException {
		checkInterrupted();
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("El ciclo no debe envolver la red en una transacción de base de datos");
		}
		String local = ServerId.requireValid(node.getLocalServerId());
		JsonNode identity = query(local,"resource=node","QUERY_NODE");
		require(
		    masterServerId.equals(identity.path("serverId").asText()) && !masterServerId.equals(local)
		            && "MASTER".equals(identity.path("role").asText()) && identity.path("protocolVersion").asInt() == 2,
		    "Identidad del maestro inesperada");
		JsonNode status = query(local,"resource=status&origin=" + local,"QUERY_STATUS");
		checkOrigin(status, local);
		long acknowledged = sequence(status, "confirmedSequence");
		long highest = records.getHighestPatientSequence(local);
		require(acknowledged <= highest, "El maestro confirma registros que esta posta no conserva");
		int sent = 0;
		int received = 0;
		// Límite por ciclo: evita que nuevas altas mantengan el envío indefinidamente.
		while (acknowledged < highest && sent < 100) {
			checkInterrupted();
			List<PatientSyncEvent> page = records.getPatientEventsAfter(local, acknowledged, 1);
			require(page.size() == 1 && page.get(0).getSequence() == acknowledged + 1, "Falta un evento local consecutivo");
			final long expected = acknowledged + 1;
            String payload = page.get(0).getPayloadJson();
            audit().run(local,masterServerId,"PATIENT","SEND",SyncAudit.metadata(payload),
                    "CONFIRMED",() -> {
                JsonNode confirmation = remote.receive(payload);
                checkOrigin(confirmation,local);
                require(sequence(confirmation,"confirmedSequence") == expected,"Confirmación remota inesperada");
                return confirmation;
            });
			acknowledged++;
			sent++;
		}
		String cursor = null;
		org.openmrs.api.APIException deferred = null;
		while (true) {
			checkInterrupted();
			JsonNode origins = query(local,"resource=origins&limit=100" + (cursor == null ? "" : "&afterOrigin=" + cursor),"QUERY_ORIGINS")
			        .path("origins");
			require(origins.isArray() && origins.size() <= 100, "Lista de orígenes inválida");
			for (JsonNode entry : origins) {
				checkInterrupted();
				String origin;
				try {
					origin = ServerId.requireValid(entry.asText());
				}
				catch (IllegalArgumentException invalid) {
					throw new IOException("Origen remoto inválido");
				}
				require(cursor == null || origin.compareTo(cursor) > 0, "Orígenes sin avance consecutivo");
				cursor = origin;
				if (local.equals(origin)) {
					continue;
				}
				long after = receiver.getConfirmedPatientSequence(origin);
				// Un origen muy activo no impide atender a los demás en este ciclo.
				for (int count = 0; count < 100; count++) {
					checkInterrupted();
					JsonNode page = query(local,"resource=events&origin=" + origin + "&after=" + after + "&limit=1","QUERY_EVENTS");
					checkOrigin(page, origin);
					JsonNode events = page.path("events");
					require(events.isArray() && events.size() <= 1, "Página de eventos inválida");
					if (events.size() == 0) {
						break;
					}
					JsonNode event = events.get(0);
					checkOrigin(event, origin);
					require(after < Long.MAX_VALUE && sequence(event, "entitySequence") == after + 1,
					    "El evento recibido no es el siguiente");
					long confirmed;
					try {
						final long expected = after + 1;
                        confirmed = audit().run(masterServerId,local,"PATIENT","RECEIVE",event,"RECEIVED",() -> {
                            long value = receiver.receivePatient(event.toString());
                            require(value == expected,"Confirmación local inesperada");
                            return value;
                        });
					}
					catch (org.openmrs.api.APIException failure) {
						// Un UPDATE puede llegar antes que el CREATE de su paciente desde un origen procesado después.
						// Conserva esta confirmación, procesa otros orígenes y reintenta en el siguiente ciclo.
						deferred = failure;
						break;
					}
					require(confirmed == after + 1, "Confirmación local inesperada");
					after = confirmed;
					received++;
				}
			}
			if (origins.size() < 100) {
				break;
			}
		}
		if (deferred != null) {
			throw deferred;
		}
		return new int[] { sent, received };
	}
	
	private static void checkInterrupted() throws IOException {
		if (Thread.currentThread().isInterrupted()) {
			throw new IOException("Ciclo interrumpido por parada");
		}
	}
	
	private static void checkOrigin(JsonNode value, String origin) throws IOException {
		require(origin.equals(value.path("originServerId").asText()) && "PATIENT".equals(value.path("entityType").asText()),
		    "Origen o entidad inesperados");
	}
	
	private static long sequence(JsonNode value, String field) throws IOException {
		JsonNode number = value.path(field);
		require(number.isIntegralNumber() && number.canConvertToLong() && number.longValue() >= 0, "Secuencia inválida");
		return number.longValue();
	}
	
	private static void require(boolean condition, String message) throws IOException {
		if (!condition) {
			throw new IOException(message);
		}
	}
}

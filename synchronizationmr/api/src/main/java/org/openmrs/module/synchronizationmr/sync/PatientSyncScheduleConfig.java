package org.openmrs.module.synchronizationmr.sync;

import java.util.Map;

/** Configuración del proceso OpenMRS. No persiste ni imprime contraseñas. */
public final class PatientSyncScheduleConfig {
	
	final boolean enabled;
	
	final long intervalSeconds;
	
	final String endpoint, masterServerId, localUser, localPassword, remoteUser, remotePassword;
	
	public PatientSyncScheduleConfig(Map<String, String> environment) {
		String flag = environment.getOrDefault("SYNCMR_ENABLED", "false");
		if (!"true".equals(flag) && !"false".equals(flag)) {
			throw new IllegalArgumentException("SYNCMR_ENABLED debe ser true o false");
		}
		enabled = Boolean.parseBoolean(flag);
		if (!enabled) {
			intervalSeconds = 60;
			endpoint = masterServerId = localUser = localPassword = remoteUser = remotePassword = null;
			return;
		}
		try {
			intervalSeconds = Long.parseLong(environment.getOrDefault("SYNCMR_INTERVAL_SECONDS", "60"));
		}
		catch (NumberFormatException invalid) {
			throw new IllegalArgumentException("Intervalo de sincronización inválido");
		}
		if (intervalSeconds < 10 || intervalSeconds > 86400) {
			throw new IllegalArgumentException("El intervalo debe estar entre 10 y 86400 segundos");
		}
		endpoint = required(environment, "SYNCMR_MASTER_ENDPOINT");
		masterServerId = ServerId.requireValid(required(environment, "SYNCMR_MASTER_SERVER_ID"));
		localUser = required(environment, "SYNCMR_LOCAL_USERNAME");
		localPassword = required(environment, "SYNCMR_LOCAL_PASSWORD");
		remoteUser = required(environment, "SYNCMR_REMOTE_USERNAME");
		remotePassword = required(environment, "SYNCMR_REMOTE_PASSWORD");
	}
	
	private String required(Map<String, String> values, String key) {
		String value = values.get(key);
		if (value == null || value.trim().isEmpty()) {
			throw new IllegalArgumentException("Falta configurar " + key);
		}
		return value;
	}
}

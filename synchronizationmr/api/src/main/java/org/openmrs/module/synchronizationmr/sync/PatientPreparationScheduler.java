package org.openmrs.module.synchronizationmr.sync;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UsernamePasswordCredentials;
import org.openmrs.module.synchronizationmr.api.PatientSyncService;

/** Local preparation, independent of transport and usable by MASTER and POSTA. */
public final class PatientPreparationScheduler {
	
	private final Log log = LogFactory.getLog(getClass());
	
	private final PatientSyncScheduler worker = new PatientSyncScheduler();
	
	public void start(Map<String, String> environment) {
        Config config = new Config(environment);
        if (!config.enabled) { return; }
        worker.start(() -> execute(config), config.intervalSeconds, TimeUnit.SECONDS);
        log.info("Preparación inicial de pacientes programada por lotes");
    }
	
	private void execute(Config config) {
		Context.openSession();
		try {
			Context.authenticate(new UsernamePasswordCredentials(config.user, config.password));
			int prepared = Context.getService(PatientSyncService.class).prepareExistingPatients(config.batchSize);
			if (prepared > 0) {
				log.info("Pacientes preparados en el lote: " + prepared);
			}
		}
		finally {
			Context.closeSession();
		}
		// The worker retries failures without logging patient data or credentials.
	}
	
	public void stop() {
		worker.stop();
	}
	
	static final class Config {
		
		final boolean enabled;
		
		final int batchSize;
		
		final long intervalSeconds;
		
		final String user, password;
		
		Config(Map<String, String> environment) {
			String flag = environment.getOrDefault("SYNCMR_PREPARE_EXISTING_ENABLED", "false");
			if (!"true".equals(flag) && !"false".equals(flag)) {
				throw new IllegalArgumentException("Activación de preparación inválida");
			}
			enabled = Boolean.parseBoolean(flag);
			if (!enabled) {
				batchSize = 25;
				intervalSeconds = 60;
				user = password = null;
				return;
			}
			batchSize = Integer.parseInt(environment.getOrDefault("SYNCMR_PREPARE_BATCH_SIZE", "25"));
			intervalSeconds = Long.parseLong(environment.getOrDefault("SYNCMR_PREPARE_INTERVAL_SECONDS", "60"));
			if (batchSize < 1 || batchSize > 100 || intervalSeconds < 10 || intervalSeconds > 86400) {
				throw new IllegalArgumentException("Preparación: lote 1..100 e intervalo 10..86400 segundos");
			}
			user = required(environment, "SYNCMR_LOCAL_USERNAME");
			password = required(environment, "SYNCMR_LOCAL_PASSWORD");
		}
		
		private static String required(Map<String, String> environment, String key) {
			String value = environment.get(key);
			if (value == null || value.trim().isEmpty()) {
				throw new IllegalArgumentException("Falta configurar " + key);
			}
			return value;
		}
	}
}

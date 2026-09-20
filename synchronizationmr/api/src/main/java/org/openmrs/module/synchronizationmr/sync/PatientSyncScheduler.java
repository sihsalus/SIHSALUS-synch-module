package org.openmrs.module.synchronizationmr.sync;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UsernamePasswordCredentials;

/** Un solo trabajador por instancia del módulo; espera después de terminar cada ciclo. */
public class PatientSyncScheduler {
	
	private final Log log = LogFactory.getLog(getClass());
	
	private ScheduledExecutorService executor;
	
	public synchronized void start(PatientSyncScheduleConfig config) {
        if (!config.enabled) { log.info("Sincronización periódica desactivada"); return; }
        start(() -> execute(config), config.intervalSeconds, TimeUnit.SECONDS);
        log.info("Sincronización periódica programada");
    }
	
	// Punto de prueba: permite verificar la programación sin autenticar ni esperar un minuto.
	synchronized void start(Runnable cycle, long delay, TimeUnit unit) {
        if (executor != null && !executor.isTerminated()) {
            throw new IllegalStateException("Ya existe un trabajador de sincronización activo");
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "synchronizationmr-pacientes");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try { cycle.run(); }
            catch (RuntimeException failure) {
                // No se imprime la excepción: podría incluir parámetros o datos clínicos.
                log.warn("El ciclo de sincronización falló; se reintentará en el siguiente intervalo");
            }
        }, delay, delay, unit);
    }
	
	private void execute(PatientSyncScheduleConfig config) {
		Context.openSession();
		try {
			Context.authenticate(new UsernamePasswordCredentials(config.localUser, config.localPassword));
			int[] result = PatientSyncClient.forLocalPosta(config.endpoint, config.masterServerId, config.remoteUser,
			    config.remotePassword).synchronizeOnce();
			log.info("Ciclo de pacientes completado: envíos confirmados=" + result[0] + ", recepciones confirmadas="
			        + result[1]);
		}
		catch (Exception failure) {
			if (Thread.currentThread().isInterrupted()) {
				log.info("Ciclo de sincronización interrumpido por parada del módulo");
			} else {
				log.warn("No se completó el ciclo de pacientes; se reintentará en el siguiente intervalo");
			}
		}
		finally {
			Context.closeSession();
		}
	}
	
	public synchronized void stop() {
		if (executor == null) {
			return;
		}
		executor.shutdownNow();
		try {
			if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
				log.warn("El trabajador todavía está terminando una operación; no se iniciará otro en esta instancia");
			}
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}

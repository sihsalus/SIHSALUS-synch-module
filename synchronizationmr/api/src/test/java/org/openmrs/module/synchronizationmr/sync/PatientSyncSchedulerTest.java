package org.openmrs.module.synchronizationmr.sync;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verifica el trabajador real con intervalos cortos y sin contactar servidores. */
public class PatientSyncSchedulerTest {
	
	@Test
	public void disabledDoesNotRequireCredentials() {
		PatientSyncScheduleConfig config = new PatientSyncScheduleConfig(Collections.emptyMap());
		assertFalse(config.enabled);
		PatientSyncScheduler scheduler = new PatientSyncScheduler();
		scheduler.start(config);
		scheduler.stop();
	}
	
	@Test public void validatesEnabledConfiguration() {
        Map<String, String> values = configured();
        assertEquals(60, new PatientSyncScheduleConfig(values).intervalSeconds);
        values.put("SYNCMR_INTERVAL_SECONDS", "0");
        assertThrows(IllegalArgumentException.class, () -> new PatientSyncScheduleConfig(values));
        values.put("SYNCMR_INTERVAL_SECONDS", "60");
        values.remove("SYNCMR_LOCAL_PASSWORD");
        assertThrows(IllegalArgumentException.class, () -> new PatientSyncScheduleConfig(values));
        values.put("SYNCMR_ENABLED", "sí");
        assertThrows(IllegalArgumentException.class, () -> new PatientSyncScheduleConfig(values));
    }
	
	@Test public void failedCycleDoesNotCancelNextAttempt() throws Exception {
        PatientSyncScheduler scheduler = new PatientSyncScheduler();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch retried = new CountDownLatch(1);
        try {
            scheduler.start(() -> {
                if (calls.incrementAndGet() == 1) { throw new IllegalStateException("Fallo simulado"); }
                retried.countDown();
            }, 10, TimeUnit.MILLISECONDS);
            assertTrue(retried.await(3, TimeUnit.SECONDS));
            assertTrue(calls.get() >= 2);
        } finally { scheduler.stop(); }
    }
	
	@Test public void refusesSecondWorkerAndInterruptsCurrentCycleOnStop() throws Exception {
        PatientSyncScheduler scheduler = new PatientSyncScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            scheduler.start(() -> {
                calls.incrementAndGet();
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException stopping) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            }, 10, TimeUnit.MILLISECONDS);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> scheduler.start(() -> {}, 10, TimeUnit.MILLISECONDS));
            scheduler.stop();
            assertEquals(0, interrupted.getCount());
            assertEquals(1, calls.get());
        } finally { scheduler.stop(); }
    }
	
	@Test public void canRestartAfterCleanStop() throws Exception {
        PatientSyncScheduler scheduler = new PatientSyncScheduler();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        try {
            scheduler.start(first::countDown, 10, TimeUnit.MILLISECONDS);
            assertTrue(first.await(3, TimeUnit.SECONDS));
            scheduler.stop();
            scheduler.start(second::countDown, 10, TimeUnit.MILLISECONDS);
            assertTrue(second.await(3, TimeUnit.SECONDS));
        } finally { scheduler.stop(); }
    }
	
	private Map<String, String> configured() {
        Map<String, String> values = new HashMap<>();
        values.put("SYNCMR_ENABLED", "true");
        for (String key : new String[] {"MASTER_ENDPOINT", "MASTER_UUID", "LOCAL_USERNAME", "LOCAL_PASSWORD", "REMOTE_USERNAME", "REMOTE_PASSWORD"}) {
            values.put("SYNCMR_" + key, "valor-de-prueba");
        }
        return values;
    }
}

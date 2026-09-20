package org.openmrs.module.synchronizationmr.sync;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PatientPreparationSchedulerTest {
	
	@Test
	public void disabledPreparationDoesNotNeedConnectionOrCredentials() {
		assertFalse(new PatientPreparationScheduler.Config(Collections.emptyMap()).enabled);
		PatientPreparationScheduler scheduler = new PatientPreparationScheduler();
		scheduler.start(Collections.emptyMap());
		scheduler.stop();
	}
	
	@Test
    public void preparationNeedsOnlyLocalCredentialsAndBoundedBatch() {
        Map<String, String> values = new HashMap<>();
        values.put("SYNCMR_PREPARE_EXISTING_ENABLED", "true");
        assertThrows(IllegalArgumentException.class, () -> new PatientPreparationScheduler.Config(values));
        values.put("SYNCMR_LOCAL_USERNAME", "test");
        values.put("SYNCMR_LOCAL_PASSWORD", "test");
        PatientPreparationScheduler.Config config = new PatientPreparationScheduler.Config(values);
        assertEquals(25, config.batchSize);
        assertEquals(60, config.intervalSeconds);
        values.put("SYNCMR_PREPARE_BATCH_SIZE", "101");
        assertThrows(IllegalArgumentException.class, () -> new PatientPreparationScheduler.Config(values));
        values.put("SYNCMR_PREPARE_BATCH_SIZE", "1");
        values.put("SYNCMR_PREPARE_INTERVAL_SECONDS", "0");
        assertThrows(IllegalArgumentException.class, () -> new PatientPreparationScheduler.Config(values));
    }
}

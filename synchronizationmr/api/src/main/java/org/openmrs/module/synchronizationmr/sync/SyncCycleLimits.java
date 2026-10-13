package org.openmrs.module.synchronizationmr.sync;

import java.util.Map;

/** Work limits, independent of the single-event wire format and confirmation semantics. */
public final class SyncCycleLimits {
	
	public static final SyncCycleLimits DEFAULT = new SyncCycleLimits(100, 100);
	
	final int eventsPerCycle;
	
	final int originsPageSize;
	
	public SyncCycleLimits(int eventsPerCycle, int originsPageSize) {
		if (eventsPerCycle < 1 || eventsPerCycle > 100 || originsPageSize < 1 || originsPageSize > 100) {
			throw new IllegalArgumentException("Sync limits must be between 1 and 100");
		}
		this.eventsPerCycle = eventsPerCycle;
		this.originsPageSize = originsPageSize;
	}
	
	static SyncCycleLimits from(Map<String, String> values) {
		return new SyncCycleLimits(Integer.parseInt(values.getOrDefault("SYNCMR_EVENTS_PER_CYCLE", "100")),
		        Integer.parseInt(values.getOrDefault("SYNCMR_ORIGINS_PAGE_SIZE", "100")));
	}
}

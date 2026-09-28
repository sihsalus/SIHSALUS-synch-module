package org.openmrs.module.synchronizationmr.sync;

/** Cursor advances only after the enclosing preparation transaction commits. */
public final class ObservationPreparationBatch {
	
	private final int lastEncounterId;
	
	private final int scanned;
	
	private final int published;
	
	private final boolean complete;
	
	public ObservationPreparationBatch(int lastEncounterId, int scanned, int published, boolean complete) {
		this.lastEncounterId = lastEncounterId;
		this.scanned = scanned;
		this.published = published;
		this.complete = complete;
	}
	
	public int getLastEncounterId() {
		return lastEncounterId;
	}
	
	public int getScanned() {
		return scanned;
	}
	
	public int getPublished() {
		return published;
	}
	
	public boolean isComplete() {
		return complete;
	}
}

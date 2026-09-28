package org.openmrs.module.synchronizationmr.sync;

/** Defer nested saveObs calls until their whole encounter/group has been saved. */
public final class ObservationCaptureScope implements AutoCloseable {
    private static final ThreadLocal<org.openmrs.Encounter> ACTIVE = new ThreadLocal<>();
    private final org.openmrs.Encounter previous;
    private ObservationCaptureScope(org.openmrs.Encounter encounter) {
        previous = ACTIVE.get(); ACTIVE.set(encounter);
    }
    public static boolean active(org.openmrs.Encounter encounter) {
        org.openmrs.Encounter current = ACTIVE.get();
        return current != null && (current == encounter || (current.getUuid() != null
            && encounter != null && current.getUuid().equals(encounter.getUuid())));
    }
    public static ObservationCaptureScope enter(org.openmrs.Encounter encounter) { return new ObservationCaptureScope(encounter); }
    @Override public void close() {
        if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
    }
}

package org.openmrs.module.synchronizationmr.sync;

import org.openmrs.Encounter;
import org.openmrs.Visit;

/** Delimita la cascada de una visita para evitar eventos locales por sus hijos. */
public final class VisitAnnulmentScope implements AutoCloseable {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private final String previous;
    private VisitAnnulmentScope(Visit visit) {
        previous = CURRENT.get();
        CURRENT.set(visit.getUuid());
    }
    public static VisitAnnulmentScope enter(Visit visit) { return new VisitAnnulmentScope(visit); }
    public static boolean contains(Encounter encounter) {
        return encounter != null && encounter.getVisit() != null
            && encounter.getVisit().getUuid().equals(CURRENT.get());
    }
    public static boolean contains(Visit visit) {
        return visit != null && visit.getUuid().equals(CURRENT.get());
    }
    public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}

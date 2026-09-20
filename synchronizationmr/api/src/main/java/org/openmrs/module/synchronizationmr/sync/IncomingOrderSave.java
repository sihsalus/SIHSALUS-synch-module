/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.sync;

import org.openmrs.Order;
import java.util.function.Supplier;

/** Evita recapturar como local exclusivamente el encuentro que estamos importando en este hilo. */
public final class IncomingOrderSave {
    private static final ThreadLocal<Order> CURRENT = new ThreadLocal<>();
    private IncomingOrderSave() { }
    public static boolean isReceiving(Order patient) { return CURRENT.get() == patient; }
    public static Order save(Order patient, Supplier<Order> action) {
        Order previous = CURRENT.get();
        CURRENT.set(patient);
        try { return action.get(); }
        finally {
            if (previous == null) { CURRENT.remove(); }
            else { CURRENT.set(previous); }
        }
    }
}

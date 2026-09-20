/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.api;

import org.openmrs.api.OpenmrsService;
import org.openmrs.annotation.Authorized;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recepción local, sin transporte HTTP. La confirmación se hace persistente al confirmar la
 * transacción.
 */
public interface EncounterReceiveService extends OpenmrsService {
	
	@Authorized(value = { "Receive Synchronization Records", "Add Encounters", "Add Observations", "Get Encounters" }, requireAll = true)
	@Transactional
	long receiveEncounter(String eventJson);
	
	@Authorized("View Synchronization Records")
	@Transactional(readOnly = true)
	long getConfirmedEncounterSequence(String originServerId);
}

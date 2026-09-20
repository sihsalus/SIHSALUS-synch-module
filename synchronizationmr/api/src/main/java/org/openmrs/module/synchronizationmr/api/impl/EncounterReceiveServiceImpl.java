/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.api.impl;

import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.synchronizationmr.api.EncounterReceiveService;
import org.openmrs.module.synchronizationmr.api.dao.EncounterReceiveDao;
import org.openmrs.module.synchronizationmr.sync.EncounterIncomingEvent;

public class EncounterReceiveServiceImpl extends BaseOpenmrsService implements EncounterReceiveService {
	
	private EncounterReceiveDao dao;
	
	public void setDao(EncounterReceiveDao dao) {
		this.dao = dao;
	}
	
	@Override
	public long receiveEncounter(String json) {
		return dao.receive(new EncounterIncomingEvent(json));
	}
	
	@Override
	public long getConfirmedEncounterSequence(String origin) {
		return dao.confirmed(org.openmrs.module.synchronizationmr.sync.ServerId.requireValid(origin));
	}
}

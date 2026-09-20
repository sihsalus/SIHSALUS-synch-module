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
import org.openmrs.module.synchronizationmr.api.PatientReceiveService;
import org.openmrs.module.synchronizationmr.api.dao.PatientReceiveDao;
import org.openmrs.module.synchronizationmr.sync.PatientIncomingEvent;

public class PatientReceiveServiceImpl extends BaseOpenmrsService implements PatientReceiveService {
	
	private PatientReceiveDao dao;
	
	public void setDao(PatientReceiveDao dao) {
		this.dao = dao;
	}
	
	@Override
	public long receivePatient(String json) {
		return dao.receive(new PatientIncomingEvent(json));
	}
	
	@Override
	public long getConfirmedPatientSequence(String origin) {
		return dao.confirmed(org.openmrs.module.synchronizationmr.sync.ServerId.requireValid(origin));
	}
}

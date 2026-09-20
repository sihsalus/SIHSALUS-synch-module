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
import org.openmrs.module.synchronizationmr.api.OrderReceiveService;
import org.openmrs.module.synchronizationmr.api.dao.OrderReceiveDao;
import org.openmrs.module.synchronizationmr.sync.OrderIncomingEvent;

public class OrderReceiveServiceImpl extends BaseOpenmrsService implements OrderReceiveService {
	
	private OrderReceiveDao dao;
	
	public void setDao(OrderReceiveDao dao) {
		this.dao = dao;
	}
	
	@Override
	public long receiveOrder(String json) {
		return dao.receive(new OrderIncomingEvent(json));
	}
	
	@Override
	public long getConfirmedOrderSequence(String origin) {
		return dao.confirmed(org.openmrs.module.synchronizationmr.sync.ServerId.requireValid(origin));
	}
	
	@Override
	public long countPendingOrderLinks() {
		return dao.countPendingOrderLinks();
	}
}

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
import org.openmrs.module.synchronizationmr.api.LocalNodeService;
import org.openmrs.module.synchronizationmr.api.dao.LocalNodeDao;

/** Servicio común para obtener la identidad estable de esta instalación. */
public class LocalNodeServiceImpl extends BaseOpenmrsService implements LocalNodeService {
	
	private LocalNodeDao dao;
	
	public void setDao(LocalNodeDao dao) {
		this.dao = dao;
	}
	
	@Override
	public String getOrCreateNodeUuid() {
		return dao.getOrCreateNodeUuid();
	}
}

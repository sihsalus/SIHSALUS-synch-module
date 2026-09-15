/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.synchronizationmr.sync.PatientSyncScheduler;
import org.openmrs.module.synchronizationmr.sync.PatientSyncScheduleConfig;

/**
 * This class contains the logic that is run every time this module is either started or shutdown
 */
public class SynchronizationMRActivator extends BaseModuleActivator {
	
	private Log log = LogFactory.getLog(this.getClass());
	
	private final PatientSyncScheduler patientScheduler = new PatientSyncScheduler();
	
	/**
	 * @see #started()
	 */
	public void started() {
		log.info("Módulo SynchronizationMR iniciado");
		try {
			patientScheduler.start(new PatientSyncScheduleConfig(System.getenv()));
		}
		catch (RuntimeException invalid) {
			log.error("No se pudo iniciar la sincronización periódica; revise su configuración");
		}
	}
	
	/**
	 * @see #shutdown()
	 */
	public void shutdown() {
		patientScheduler.stop();
		log.info("Módulo SynchronizationMR detenido");
	}
	
	@Override
	public void willStop() {
		// Detiene el trabajo antes de que OpenMRS retire los servicios del módulo.
		patientScheduler.stop();
	}
	
}

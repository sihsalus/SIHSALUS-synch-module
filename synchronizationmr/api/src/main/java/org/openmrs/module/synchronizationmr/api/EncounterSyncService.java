package org.openmrs.module.synchronizationmr.api;

import org.openmrs.Encounter;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Captura local de altas de encuentros. Todavía no ofrece transporte ni JSON. */
public interface EncounterSyncService extends OpenmrsService {
	
	@Transactional(propagation = Propagation.MANDATORY)
	boolean encounterExists(Integer encounterId);
	
	@Transactional(propagation = Propagation.MANDATORY)
	void recordCreatedEncounter(Encounter encounter);
}

package org.openmrs.module.synchronizationmr.api;

import org.openmrs.Encounter;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Captura y consulta de eventos de encuentros para transporte JSON. */
public interface EncounterSyncService extends OpenmrsService {
	
	@Authorized(value = { "Prepare Synchronization Records", "Get Encounters", "Get Observations" }, requireAll = true)
	@Transactional
	int prepareExistingEncounters(int limit);
	
	@Authorized("View Synchronization Records")
	@Transactional(readOnly = true)
	long countEncountersPendingPreparation();
	
	@Authorized("View Synchronization Records")
	@Transactional(readOnly = true)
	java.util.List<String> getEncounterOrigins(String afterOrigin, int limit);
	
	@Authorized("View Synchronization Records")
	@Transactional(readOnly = true)
	long getHighestEncounterSequence(String origin);
	
	@Authorized(value = { "View Synchronization Records", "Get Encounters", "Get Observations" }, requireAll = true)
	@Transactional(readOnly = true)
	java.util.List<org.openmrs.module.synchronizationmr.sync.EncounterSyncEvent> getEncounterEventsAfter(String origin,
	        long after, int limit);
	
	@Transactional(propagation = Propagation.MANDATORY)
	boolean encounterExists(Integer encounterId);
	
	@Transactional(propagation = Propagation.MANDATORY)
	void recordCreatedEncounter(Encounter encounter);
}

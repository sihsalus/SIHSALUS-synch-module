package org.openmrs.module.synchronizationmr.api;

import org.openmrs.Encounter;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Captura y consulta de eventos de encuentros para transporte JSON. */
public interface EncounterSyncService extends OpenmrsService {
	
	@Transactional(propagation = Propagation.MANDATORY)
	void recordVisitInterval(org.openmrs.Visit visit);
	
	/** Prepara las anulaciones publicadas que todavia no tienen evento propio. */
	@Authorized(value = { "Prepare Synchronization Records", "Get Encounters", "Get Observations", "Edit Observations" }, requireAll = true)
	@Transactional
	int prepareVoidedObservations(String encounterUuid);
	
	/** Prepara explícitamente atributos omitidos por un CREATE propio antiguo. */
	@Authorized(value = { "Prepare Synchronization Records", "Get Encounters", "Get Visits", "Get Visit Attribute Types" }, requireAll = true)
	@Transactional
	boolean prepareVisitAttributes(String encounterUuid);
	
	@Authorized(value = { "Prepare Synchronization Records", "Get Encounters", "Get Observations" }, requireAll = true)
	@Transactional
	int prepareExistingEncounters(int limit);
	
	/** Explicit scan of unpublished, original observations on previously published encounters. */
	@Authorized(value = { "Prepare Synchronization Records", "Get Encounters", "Get Observations" }, requireAll = true)
	@Transactional
	org.openmrs.module.synchronizationmr.sync.ObservationPreparationBatch prepareExistingObservations(int afterEncounterId,
	        int limit);
	
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
	void lockEncounterChanges();
	
	@Transactional(propagation = Propagation.MANDATORY)
	void recordChangedEncounter(Encounter encounter);
	
	@Transactional(propagation = Propagation.MANDATORY)
	java.util.Map<String, String> snapshotObservationReplacements(Encounter encounter);
	
	@Transactional(propagation = Propagation.MANDATORY)
	void linkObservationReplacements(Encounter encounter, java.util.Map<String, String> before);
	
	@Transactional(propagation = Propagation.MANDATORY)
	void recordVoidedEncounter(Encounter encounter);
	
	@Transactional(propagation = Propagation.MANDATORY)
	void recordCreatedEncounter(Encounter encounter);
	
	@Transactional(propagation = Propagation.MANDATORY)
	void recordAddedObservations(Encounter encounter);
	
}

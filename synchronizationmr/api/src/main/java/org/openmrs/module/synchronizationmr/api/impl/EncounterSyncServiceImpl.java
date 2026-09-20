package org.openmrs.module.synchronizationmr.api.impl;

import org.openmrs.Encounter;
import org.openmrs.api.APIException;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.synchronizationmr.api.EncounterSyncService;
import org.openmrs.module.synchronizationmr.api.dao.EncounterSyncDao;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class EncounterSyncServiceImpl extends BaseOpenmrsService implements EncounterSyncService {
	
	@Override
	public int prepareExistingEncounters(int batchSize) {
		requireTransaction();
		limit(batchSize);
		java.util.List<Integer> ids = dao.lockAndFindPending(batchSize);
		for (Integer id : ids) {
			if (Thread.currentThread().isInterrupted()) {
				throw new APIException("Preparación interrumpida");
			}
			Encounter encounter = org.openmrs.api.context.Context.getEncounterService().getEncounter(id);
			if (encounter == null || Boolean.TRUE.equals(encounter.getVoided())) {
				throw new APIException("Encuentro inexistente o anulado");
			}
			dao.capture(encounter);
			dao.requirePayload(id);
		}
		return ids.size();
	}
	
	@Override
	public long countEncountersPendingPreparation() {
		return dao.countPending();
	}
	
	private void limit(int limit) {
		if (limit < 1 || limit > 100) {
			throw new APIException("El límite debe estar entre 1 y 100");
		}
	}
	
	@Override
	public java.util.List<String> getEncounterOrigins(String after, int limit) {
		limit(limit);
		return dao.findEncounterOrigins(
		    after == null ? "" : org.openmrs.module.synchronizationmr.sync.ServerId.requireValid(after), limit);
	}
	
	@Override
	public long getHighestEncounterSequence(String origin) {
		return dao.findHighestEncounterSequence(org.openmrs.module.synchronizationmr.sync.ServerId.requireValid(origin));
	}
	
	@Override
	public java.util.List<org.openmrs.module.synchronizationmr.sync.EncounterSyncEvent> getEncounterEventsAfter(
	        String origin, long after, int limit) {
		limit(limit);
		if (after < 0) {
			throw new APIException("La secuencia no puede ser negativa");
		}
		return dao.findEncounterEventsAfter(org.openmrs.module.synchronizationmr.sync.ServerId.requireValid(origin), after,
		    limit);
	}
	
	private EncounterSyncDao dao;
	
	public void setDao(EncounterSyncDao dao) {
		this.dao = dao;
	}
	
	private void requireTransaction() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
		        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new APIException("La captura del encuentro requiere una transacción de escritura");
		}
	}
	
	@Override
	public boolean encounterExists(Integer id) {
		requireTransaction();
		return dao.exists(id);
	}
	
	@Override
	public void recordCreatedEncounter(Encounter encounter) {
		requireTransaction();
		if (encounter == null || encounter.getEncounterId() == null || encounter.getUuid() == null
		        || encounter.getPatient() == null || encounter.getPatient().getPatientId() == null) {
			throw new APIException("Se requiere un encuentro guardado con su paciente local");
		}
		dao.capture(encounter);
	}
}

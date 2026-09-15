package org.openmrs.module.synchronizationmr.api.impl;

import org.openmrs.Encounter;
import org.openmrs.api.APIException;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.synchronizationmr.api.EncounterSyncService;
import org.openmrs.module.synchronizationmr.api.dao.EncounterSyncDao;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class EncounterSyncServiceImpl extends BaseOpenmrsService implements EncounterSyncService {
	
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

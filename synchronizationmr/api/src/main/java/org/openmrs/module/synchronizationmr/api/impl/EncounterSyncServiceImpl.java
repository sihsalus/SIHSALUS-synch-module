package org.openmrs.module.synchronizationmr.api.impl;

import org.openmrs.Encounter;
import org.openmrs.api.APIException;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.synchronizationmr.api.EncounterSyncService;
import org.openmrs.module.synchronizationmr.api.dao.EncounterSyncDao;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class EncounterSyncServiceImpl extends BaseOpenmrsService implements EncounterSyncService {
	
	@javax.annotation.Resource(name = "synchronizationmr.VisitUpdateDao")
	private org.openmrs.module.synchronizationmr.api.dao.VisitUpdateDao visits;
	
	@Override
	public void recordVisitInterval(org.openmrs.Visit visit) {
		requireTransaction();
		visits.record(visit);
	}
	
	@javax.annotation.Resource(name = "synchronizationmr.EncounterVisitSupplementDao")
	private org.openmrs.module.synchronizationmr.api.dao.EncounterVisitSupplementDao visitSupplements;
	
	@Override
	public boolean prepareVisitAttributes(String encounterUuid) {
		requireTransaction();
		if (encounterUuid == null || encounterUuid.trim().isEmpty())
			throw new APIException("Falta el UUID del encuentro");
		return visitSupplements.prepare(encounterUuid);
	}
	
	@Override
	public org.openmrs.module.synchronizationmr.sync.ObservationPreparationBatch prepareExistingObservations(
	        int afterEncounterId, int batchSize) {
		requireTransaction();
		limit(batchSize);
		if (afterEncounterId < 0)
			throw new APIException("El cursor no puede ser negativo");
		java.util.List<Integer> ids = dao.findPublishedEncountersAfter(afterEncounterId, batchSize + 1);
		int scanned = Math.min(ids.size(), batchSize), published = 0, last = afterEncounterId;
		for (int i = 0; i < scanned; i++) {
			if (Thread.currentThread().isInterrupted())
				throw new APIException("Preparacion interrumpida");
			int id = ids.get(i);
			Encounter encounter = org.openmrs.api.context.Context.getEncounterService().getEncounter(id);
			if (encounter == null || Boolean.TRUE.equals(encounter.getVoided())) {
				throw new APIException("Encuentro inexistente o anulado durante la preparacion");
			}
			dao.requirePayload(id);
			if (dao.captureAdditions(encounter, true))
				published++;
			last = id;
		}
		return new org.openmrs.module.synchronizationmr.sync.ObservationPreparationBatch(last, scanned, published,
		        ids.size() <= batchSize);
	}
	
	@Override
	public int prepareExistingEncounters(int batchSize) {
		requireTransaction();
		limit(batchSize);
		java.util.List<Integer> ids = dao.lockAndFindPending(batchSize);
		for (Integer id : ids) {
			if (Thread.currentThread().isInterrupted()) {
				throw new APIException("PreparaciÃ³n interrumpida");
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
			throw new APIException("El lÃ­mite debe estar entre 1 y 100");
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
	
	@javax.annotation.Resource(name = "synchronizationmr.ObservationReplacementDao")
	private org.openmrs.module.synchronizationmr.api.dao.ObservationReplacementDao replacements;
	
	@Override
	public java.util.Map<String, String> snapshotObservationReplacements(Encounter encounter) {
		requireTransaction();
		return replacements.before(encounter);
	}
	
	@Override
	public void linkObservationReplacements(Encounter encounter, java.util.Map<String, String> before) {
		requireTransaction();
		replacements.link(encounter, before);
	}
	
	@javax.annotation.Resource(name = "synchronizationmr.EncounterVoidDao")
	private org.openmrs.module.synchronizationmr.api.dao.EncounterVoidDao annulments;
	
	@Override
	public void recordVoidedEncounter(Encounter encounter) {
		requireTransaction();
		annulments.capture(encounter);
	}
	
	@javax.annotation.Resource(name = "synchronizationmr.ObservationCorrectionDao")
	private org.openmrs.module.synchronizationmr.api.dao.ObservationCorrectionDao corrections;
	
	@javax.annotation.Resource(name = "synchronizationmr.ObservationVoidDao")
	private org.openmrs.module.synchronizationmr.api.dao.ObservationVoidDao voids;
	
	@Override
	public int prepareVoidedObservations(String encounterUuid) {
		requireTransaction();
		Encounter encounter = org.openmrs.api.context.Context.getEncounterService().getEncounterByUuid(encounterUuid);
		if (encounter == null)
			throw new APIException("Encuentro inexistente");
		return voids.capture(encounter);
	}
	
	@javax.annotation.Resource(name = "synchronizationmr.EncounterUpdateDao")
	private org.openmrs.module.synchronizationmr.api.dao.EncounterUpdateDao updates;
	
	@Override
	public void lockEncounterChanges() {
		requireTransaction();
		updates.lock();
	}
	
	@Override
	public void recordChangedEncounter(Encounter encounter) {
		requireTransaction();
		if (encounter != null && encounter.getEncounterId() != null)
			updates.recordSaved(encounter);
	}
	
	@Override
	public void recordAddedObservations(Encounter encounter) {
		requireTransaction();
		if (encounter != null && encounter.getEncounterId() != null) {
			corrections.capture(encounter);
			dao.captureAdditions(encounter);
			voids.capture(encounter);
		}
	}
	
	public void setDao(EncounterSyncDao dao) {
		this.dao = dao;
	}
	
	private void requireTransaction() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
		        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new APIException("La captura del encuentro requiere una transacciÃ³n de escritura");
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

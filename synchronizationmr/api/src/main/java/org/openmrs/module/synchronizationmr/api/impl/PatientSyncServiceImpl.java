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

import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.module.synchronizationmr.sync.ServerId;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.synchronizationmr.api.PatientSyncService;
import org.openmrs.module.synchronizationmr.api.dao.PatientSyncDao;
import org.openmrs.module.synchronizationmr.sync.PatientSyncRecord;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class PatientSyncServiceImpl extends BaseOpenmrsService implements PatientSyncService {
	
	@Override
	public int prepareExistingPatients(int limit) {
		requireWriteTransaction();
		if (limit < 1 || limit > 100) {
			throw new APIException("El lote debe estar entre 1 y 100 pacientes");
		}
		java.util.List<Integer> ids = dao.lockAndFindPatientsPendingPreparation(limit);
		for (Integer id : ids) {
			if (Thread.currentThread().isInterrupted()) {
				throw new APIException("Preparación interrumpida; el lote no se confirma");
			}
			ensurePatientSyncRecord(id);
		}
		return ids.size();
	}
	
	@Override
	public long countPatientsPendingPreparation() {
		return dao.countPatientsPendingPreparation();
	}
	
	@Override
	public PatientSyncRecord ensurePatientSyncRecord(Integer localPatientId) {
		requireWriteTransaction();
		if (localPatientId == null || localPatientId <= 0) {
			throw new APIException("Se requiere el identificador interno de un paciente local guardado");
		}
		// Carga la entidad local real; no acepta una copia enviada desde otra instancia.
		Patient patient = org.openmrs.api.context.Context.getPatientService().getPatient(localPatientId);
		if (patient == null || Boolean.TRUE.equals(patient.getVoided())) {
			throw new APIException("No se puede preparar un paciente inexistente o anulado");
		}
		// Reutiliza el bloqueo del contador y la comprobación de identidad del alta.
		// Si ya vino de otro nodo, conserva ese origen; no lo convierte en una nueva alta local.
		PatientSyncRecord record = dao.recordCreation(patient);
		String payload = dao.findCreationPayload(patient.getUuid());
		if (payload == null || payload.trim().isEmpty()) {
			throw new APIException(
			        "El registro existente no tiene JSON; requiere revisión y no se regenerará automáticamente");
		}
		return record;
	}
	
	@Override
	public java.util.List<String> getPatientOrigins(String afterOriginServerId, int limit) {
		if (limit < 1 || limit > 100) {
			throw new APIException("El límite debe estar entre 1 y 100");
		}
		return dao.findPatientOrigins(afterOriginServerId == null ? "" : validateOrigin(afterOriginServerId), limit);
	}
	
	@Override
	public long getHighestPatientSequence(String originServerId) {
		return dao.findHighestPatientSequence(validateOrigin(originServerId));
	}
	
	@Override
	public java.util.List<org.openmrs.module.synchronizationmr.sync.PatientSyncEvent> getPatientEventsAfter(
	        String originServerId, long afterSequence, int limit) {
		String origin = validateOrigin(originServerId);
		if (afterSequence < 0 || limit < 1 || limit > 100) {
			throw new APIException("La secuencia debe ser cero o positiva y el límite debe estar entre 1 y 100");
		}
		return dao.findPatientEventsAfter(origin, afterSequence, limit);
	}
	
	private String validateOrigin(String origin) {
		if (!ServerId.isValid(origin)) {
			throw new APIException("Se requiere un server.id de origen válido");
		}
		return origin;
	}
	
	@Override
	public String getCreationPayload(String patientUuid) {
		if (patientUuid == null || patientUuid.trim().isEmpty()) {
			throw new APIException("Se requiere el UUID del paciente");
		}
		return dao.findCreationPayload(patientUuid);
	}
	
	private PatientSyncDao dao;
	
	public void setDao(PatientSyncDao dao) {
		this.dao = dao;
	}
	
	@Override
	public boolean patientExists(Integer patientId) {
		requireWriteTransaction();
		return dao.patientExists(patientId);
	}
	
	@Override
	public PatientSyncRecord recordCreatedPatient(Patient patient) {
		requireWriteTransaction();
		if (patient == null || patient.getPatientId() == null || patient.getUuid() == null
		        || patient.getUuid().trim().isEmpty()) {
			throw new APIException("Para registrar la sincronización se necesita un paciente guardado y con UUID");
		}
		return dao.recordCreation(patient);
	}
	
	@Override
	public PatientSyncRecord getByPatientUuid(String uuid) {
		if (uuid == null || uuid.trim().isEmpty()) {
			throw new APIException("Se requiere el UUID del paciente");
		}
		return dao.findByPatientUuid(uuid);
	}
	
	private void requireWriteTransaction() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
		        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new APIException("El registro de sincronización requiere la transacción de escritura clínica activa");
		}
	}
}

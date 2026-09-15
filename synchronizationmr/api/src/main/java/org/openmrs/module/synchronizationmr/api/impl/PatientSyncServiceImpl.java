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
import org.openmrs.api.AdministrationService;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.synchronizationmr.api.PatientSyncService;
import org.openmrs.module.synchronizationmr.api.dao.PatientSyncDao;
import org.openmrs.module.synchronizationmr.sync.PatientSyncRecord;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class PatientSyncServiceImpl extends BaseOpenmrsService implements PatientSyncService {
	
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
		PatientSyncRecord record = dao.recordCreation(patient, nodeLabel());
		String payload = dao.findCreationPayload(patient.getUuid());
		if (payload == null || payload.trim().isEmpty()) {
			throw new APIException(
			        "El registro existente no tiene JSON; requiere revisión y no se regenerará automáticamente");
		}
		return record;
	}
	
	@Override
	public java.util.List<String> getPatientOrigins(String afterOriginUuid, int limit) {
		if (limit < 1 || limit > 100) {
			throw new APIException("El límite debe estar entre 1 y 100");
		}
		return dao.findPatientOrigins(afterOriginUuid == null ? "" : validateOrigin(afterOriginUuid), limit);
	}
	
	@Override
	public long getHighestPatientSequence(String originNodeUuid) {
		return dao.findHighestPatientSequence(validateOrigin(originNodeUuid));
	}
	
	@Override
	public java.util.List<org.openmrs.module.synchronizationmr.sync.PatientSyncEvent> getPatientEventsAfter(
	        String originNodeUuid, long afterSequence, int limit) {
		String origin = validateOrigin(originNodeUuid);
		if (afterSequence < 0 || limit < 1 || limit > 100) {
			throw new APIException("La secuencia debe ser cero o positiva y el límite debe estar entre 1 y 100");
		}
		return dao.findPatientEventsAfter(origin, afterSequence, limit);
	}
	
	private String validateOrigin(String origin) {
		if (origin == null || !origin.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
			throw new APIException("Se requiere un UUID de origen válido");
		}
		return origin.toLowerCase(java.util.Locale.ROOT);
	}
	
	@Override
	public String getCreationPayload(String patientUuid) {
		if (patientUuid == null || patientUuid.trim().isEmpty()) {
			throw new APIException("Se requiere el UUID del paciente");
		}
		return dao.findCreationPayload(patientUuid);
	}
	
	public static final String NODE_LABEL_PROPERTY = "synchronizationmr.nodeLabel";
	
	private PatientSyncDao dao;
	
	private AdministrationService administrationService;
	
	public void setDao(PatientSyncDao dao) {
		this.dao = dao;
	}
	
	public void setAdministrationService(AdministrationService service) {
		this.administrationService = service;
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
		return dao.recordCreation(patient, nodeLabel());
	}
	
	@Override
	public PatientSyncRecord getByPatientUuid(String uuid) {
		if (uuid == null || uuid.trim().isEmpty()) {
			throw new APIException("Se requiere el UUID del paciente");
		}
		return dao.findByPatientUuid(uuid, nodeLabel());
	}
	
	private String nodeLabel() {
		String label = administrationService.getGlobalProperty(NODE_LABEL_PROPERTY);
		if (label == null || label.trim().isEmpty()) {
			return "NODO-LOCAL";
		}
		return label.trim();
	}
	
	private void requireWriteTransaction() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
		        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new APIException("El registro de sincronización requiere la transacción de escritura clínica activa");
		}
	}
}

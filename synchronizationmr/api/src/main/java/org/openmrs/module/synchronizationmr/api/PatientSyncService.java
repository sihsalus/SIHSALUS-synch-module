/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.api;

import org.openmrs.Patient;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.synchronizationmr.sync.PatientSyncRecord;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface PatientSyncService extends OpenmrsService {
	
	/** Orígenes conocidos, ordenados por UUID y paginados; null inicia la primera página. */
	@Authorized("View Synchronization Records")
	@Transactional(readOnly = true)
	java.util.List<String> getPatientOrigins(String afterOriginUuid, int limit);
	
	/** Mayor secuencia registrada para este origen, o cero. No es una confirmación consecutiva. */
	@Authorized("View Synchronization Records")
	@Transactional(readOnly = true)
	long getHighestPatientSequence(String originNodeUuid);
	
	/**
	 * Consulta exclusiva después de afterSequence, de 1 a 100 eventos, ordenados. Un hueco o evento
	 * sin contenido aborta la página; nunca confirma ni modifica entregas.
	 */
	@Authorized(value = { "View Synchronization Records", "Get Patients" }, requireAll = true)
	@Transactional(readOnly = true)
	java.util.List<org.openmrs.module.synchronizationmr.sync.PatientSyncEvent> getPatientEventsAfter(String originNodeUuid,
	        long afterSequence, int limit);
	
	/**
	 * JSON original de creación. Devuelve null si no hay evento o si es anterior a esta captura.
	 * Requiere ambos permisos porque contiene datos personales.
	 */
	@Authorized(value = { "View Synchronization Records", "Get Patients" }, requireAll = true)
	@Transactional(readOnly = true)
	String getCreationPayload(String patientUuid);
	
	/** Comprueba si ya existe como paciente, incluso si antes estaba registrado solo como persona. */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean patientExists(Integer patientId);
	
	/** Registra la creación dentro de la misma transacción que guarda los datos clínicos. */
	@Transactional(propagation = Propagation.MANDATORY)
	PatientSyncRecord recordCreatedPatient(Patient patient);
	
	@Authorized("View Synchronization Records")
	@Transactional(readOnly = true)
	PatientSyncRecord getByPatientUuid(String patientUuid);
}

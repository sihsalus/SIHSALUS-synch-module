/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.sync;

/** Evento consultado para entrega. Contiene datos personales; no debe imprimirse en registros. */
public final class PatientSyncEvent {
	
	private final String originNodeUuid;
	
	private final long sequence;
	
	private final String eventUuid;
	
	private final String patientUuid;
	
	private final String payloadJson;
	
	public PatientSyncEvent(String originNodeUuid, long sequence, String eventUuid, String patientUuid, String payloadJson) {
		this.originNodeUuid = originNodeUuid;
		this.sequence = sequence;
		this.eventUuid = eventUuid;
		this.patientUuid = patientUuid;
		this.payloadJson = payloadJson;
	}
	
	public String getOriginNodeUuid() {
		return originNodeUuid;
	}
	
	public String getEntityType() {
		return "PATIENT";
	}
	
	public long getSequence() {
		return sequence;
	}
	
	public String getEventUuid() {
		return eventUuid;
	}
	
	public String getPatientUuid() {
		return patientUuid;
	}
	
	public String getPayloadJson() {
		return payloadJson;
	}
}

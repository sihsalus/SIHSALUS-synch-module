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

import java.util.Date;

/** Permite consultar la identidad y el evento de creación pendiente, sin incluir datos personales. */
public class PatientSyncRecord {
	
	private final String originNodeUuid;
	
	private final String nodeLabel;
	
	private final long sequence;
	
	private final String patientUuid;
	
	private final String eventUuid;
	
	private final String state;
	
	private final Date dateCreated;
	
	public PatientSyncRecord(String originNodeUuid, String nodeLabel, long sequence, String patientUuid, String eventUuid,
	    String state, Date dateCreated) {
		this.originNodeUuid = originNodeUuid;
		this.nodeLabel = nodeLabel;
		this.sequence = sequence;
		this.patientUuid = patientUuid;
		this.eventUuid = eventUuid;
		this.state = state;
		this.dateCreated = new Date(dateCreated.getTime());
	}
	
	public String getOriginNodeUuid() {
		return originNodeUuid;
	}
	
	public String getNodeLabel() {
		return nodeLabel;
	}
	
	public String getEntityType() {
		return "PATIENT";
	}
	
	public long getSequence() {
		return sequence;
	}
	
	public String getPatientUuid() {
		return patientUuid;
	}
	
	public String getEventUuid() {
		return eventUuid;
	}
	
	public String getState() {
		return state;
	}
	
	public Date getDateCreated() {
		return new Date(dateCreated.getTime());
	}
	
	public String getDisplayIdentifier() {
		return nodeLabel + " / PACIENTE / " + sequence;
	}
}

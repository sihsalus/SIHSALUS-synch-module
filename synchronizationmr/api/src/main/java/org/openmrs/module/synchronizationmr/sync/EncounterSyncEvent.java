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
public final class EncounterSyncEvent {
	
	private final String originServerId;
	
	private final long sequence;
	
	private final String eventUuid;
	
	private final String encounterUuid;
	
	private final String payloadJson;
	
	public EncounterSyncEvent(String originServerId, long sequence, String eventUuid, String encounterUuid,
	    String payloadJson) {
		this.originServerId = originServerId;
		this.sequence = sequence;
		this.eventUuid = eventUuid;
		this.encounterUuid = encounterUuid;
		this.payloadJson = payloadJson;
	}
	
	public String getOriginServerId() {
		return originServerId;
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
	
	public String getEncounterUuid() {
		return encounterUuid;
	}
	
	public String getPayloadJson() {
		return payloadJson;
	}
}

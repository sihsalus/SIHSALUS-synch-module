package org.openmrs.module.synchronizationmr.sync;

/** CREATE and observation additions use one ordered transport stream and receipt. */
public final class EncounterEventStream {
	
	private EncounterEventStream() {
	}
	
	public static final String SQL = "(select event_uuid, encounter_id, encounter_uuid, origin_server_id,"
	        + " entity_sequence, date_created, payload_json from synchronizationmr_encounter_event"
	        + " union all select event_uuid, encounter_id, encounter_uuid, origin_server_id,"
	        + " entity_sequence, date_created, payload_json from synchronizationmr_encounter_addition)";
}

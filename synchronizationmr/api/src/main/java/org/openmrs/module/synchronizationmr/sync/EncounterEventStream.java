package org.openmrs.module.synchronizationmr.sync;

/** CREATE, UPDATE y adiciones comparten el contador, las pÃ¡ginas y el recibo de encuentros. */
public final class EncounterEventStream {

	private EncounterEventStream() {
	}

	public static final String SQL = "(select event_uuid, encounter_id, encounter_uuid, origin_server_id,"
	        + " entity_sequence, date_created, payload_json from synchronizationmr_encounter_event"
	        + " union all select event_uuid, encounter_id, encounter_uuid, origin_server_id,"
	        + " entity_sequence, date_created, payload_json from synchronizationmr_encounter_addition"
	        + " union all select event_uuid, encounter_id, encounter_uuid, origin_server_id,"
	        + " entity_sequence, date_created, payload_json from synchronizationmr_encounter_update"
	        + " union all select event_uuid, encounter_id, encounter_uuid, origin_server_id,"
	        + " entity_sequence, date_created, payload_json from synchronizationmr_encounter_correction"
	        + " union all select event_uuid, encounter_id, encounter_uuid, origin_server_id,"
	        + " entity_sequence, date_created, payload_json from synchronizationmr_encounter_void"
	        + " union all select event_uuid, encounter_id, encounter_uuid, origin_server_id,"
	        + " entity_sequence, date_created, payload_json from synchronizationmr_encounter_annulment)";
}

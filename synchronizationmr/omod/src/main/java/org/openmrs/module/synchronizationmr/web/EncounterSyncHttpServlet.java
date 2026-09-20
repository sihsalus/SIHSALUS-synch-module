package org.openmrs.module.synchronizationmr.web;

import java.util.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.api.*;
import org.openmrs.module.synchronizationmr.sync.*;

/** Same authenticated HTTPS protocol as patients, with independent encounter sequences. */
public class EncounterSyncHttpServlet extends PatientSyncHttpServlet {
	
	protected EncounterSyncService encounters() {
		return Context.getService(EncounterSyncService.class);
	}
	
	protected EncounterReceiveService encounterReceiver() {
		return Context.getService(EncounterReceiveService.class);
	}
	
	@Override
	protected void authorizeReceive(PatientSyncPeerSession peer, String origin) {
		peer.authorizeEncounterReceive(origin);
	}
	
	@Override
	protected String entityType() {
		return "ENCOUNTER";
	}
	
	@Override
	protected String incomingOrigin(String json) {
		return new EncounterIncomingEvent(json).origin;
	}
	
	@Override
	protected long receiveEvent(String json) {
		return encounterReceiver().receiveEncounter(json);
	}
	
	@Override
	protected List<String> origins(String after, int limit) {
		return encounters().getEncounterOrigins(after, limit);
	}
	
	@Override
	protected long highest(String origin) {
		return encounters().getHighestEncounterSequence(origin);
	}
	
	@Override
	protected long confirmed(String origin) {
		return encounterReceiver().getConfirmedEncounterSequence(origin);
	}
	
	@Override protected List<String> payloads(String origin, long after, int limit) {
        List<String> result = new ArrayList<>();
        for (EncounterSyncEvent event : encounters().getEncounterEventsAfter(origin, after, limit)) { result.add(event.getPayloadJson()); }
        return result;
    }
}

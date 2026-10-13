package org.openmrs.module.synchronizationmr.sync;

import java.io.IOException;
import java.util.Collections;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openmrs.module.synchronizationmr.api.LocalNodeService;
import org.openmrs.module.synchronizationmr.api.EncounterReceiveService;
import org.openmrs.module.synchronizationmr.api.EncounterSyncService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pruebas del ciclo y de su reanudación, con servicios y red simulados. */
public class EncounterSyncClientTest {
	
	private static final String LOCAL = "testServer_1";
	
	private static final String MASTER = "testServer_2";
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private final LocalNodeService node = mock(LocalNodeService.class);
	
	private final EncounterSyncService records = mock(EncounterSyncService.class);
	
	private final EncounterReceiveService receiver = mock(EncounterReceiveService.class);
	
	private final PatientRemoteTransport remote = mock(PatientRemoteTransport.class);
	
	private final org.openmrs.module.synchronizationmr.api.SyncAuditService auditService = mock(org.openmrs.module.synchronizationmr.api.SyncAuditService.class);
	
	private final EncounterSyncClient client = new EncounterSyncClient(node, records, receiver, remote, MASTER,
	        new SyncAudit(auditService));
	
	private ObjectNode envelope(String origin) {
		return mapper.createObjectNode().put("originServerId", origin).put("entityType", "ENCOUNTER");
	}
	
	private void prepare(long highest, long confirmed) throws Exception {
		when(node.getLocalServerId()).thenReturn(LOCAL);
		when(remote.get("resource=node")).thenReturn(
		    mapper.createObjectNode().put("serverId", MASTER).put("role", "MASTER").put("protocolVersion", 2));
		when(remote.get("resource=status&origin=" + LOCAL)).thenReturn(envelope(LOCAL).put("confirmedSequence", confirmed));
		when(records.getHighestEncounterSequence(LOCAL)).thenReturn(highest);
		ObjectNode origins = mapper.createObjectNode();
		origins.putArray("origins").add(LOCAL).add(MASTER);
		when(remote.get("resource=origins&limit=100")).thenReturn(origins);
		when(remote.get(events(0))).thenReturn(emptyPage());
	}
	
	@Test
	public void rejectsUnconfiguredLocalIdentityBeforeNetwork() {
		when(node.getLocalServerId()).thenThrow(new org.openmrs.api.APIException("Falta server.id"));
		assertThrows(org.openmrs.api.APIException.class, client::synchronizeOnce);
		verifyNoInteractions(remote, records, receiver);
	}
	
	@Test
	public void rejectsMasterWithSameServerIdBeforeExchangingEncounters() throws Exception {
		prepare(1, 0);
		when(remote.get("resource=node")).thenReturn(mapper.createObjectNode().put("serverId", MASTER)
		        .put("role", "MASTER").put("protocolVersion", 2).put("serverId", LOCAL));
		assertThrows(IOException.class, client::synchronizeOnce);
		verify(remote, never()).receive(anyString());
		verifyNoInteractions(records, receiver);
	}
	
	@Test
	public void acceptsDistinctConfiguredServerId() throws Exception {
		prepare(0, 0);
		when(remote.get("resource=node")).thenReturn(
		    mapper.createObjectNode().put("serverId", MASTER).put("role", "MASTER").put("protocolVersion", 2)
		            .put("serverId", MASTER));
		assertArrayEquals(new int[] { 0, 0 }, client.synchronizeOnce());
	}
	
	@Test
	public void missingEncounterFromEarlierOriginDoesNotStarveLaterOrigin() throws Exception {
		prepare(0, 0);
		String early = "posta_a", later = "posta_b";
		ObjectNode origins = mapper.createObjectNode();
		origins.putArray("origins").add(early).add(later);
		when(remote.get("resource=origins&limit=100")).thenReturn(origins);
		ObjectNode earlyEvent = envelope(early).put("entitySequence", 1);
		ObjectNode laterEvent = envelope(later).put("entitySequence", 1);
		ObjectNode earlyPage = envelope(early);
		earlyPage.putArray("events").add(earlyEvent);
		ObjectNode laterPage = envelope(later);
		laterPage.putArray("events").add(laterEvent);
		when(remote.get("resource=events&origin=" + early + "&after=0&limit=1")).thenReturn(earlyPage);
		when(remote.get("resource=events&origin=" + later + "&after=0&limit=1")).thenReturn(laterPage);
		ObjectNode empty = envelope(later);
		empty.putArray("events");
		when(remote.get("resource=events&origin=" + later + "&after=1&limit=1")).thenReturn(empty);
		when(receiver.receiveEncounter(earlyEvent.toString())).thenThrow(
		    new EncounterDependencyException("missing encounter"));
		when(receiver.receiveEncounter(laterEvent.toString())).thenReturn(1L);
		assertArrayEquals(new int[] { 0, 1 }, client.synchronizeOnce());
		verify(receiver).receiveEncounter(laterEvent.toString());
		verify(remote, never()).get("resource=events&origin=" + early + "&after=1&limit=1");
	}
	
	@Test
	public void rejectsOldProtocolEvenIfServerNameMatches() throws Exception {
		prepare(1, 0);
		when(remote.get("resource=node")).thenReturn(mapper.createObjectNode().put("serverId", MASTER)
		        .put("role", "MASTER").put("protocolVersion", 1));
		assertThrows(IOException.class, client::synchronizeOnce);
		verifyNoInteractions(records, receiver);
	}
	
	private String events(long after) {
		return "resource=events&origin=" + MASTER + "&after=" + after + "&limit=1";
	}
	
	private ObjectNode emptyPage() {
		ObjectNode result = envelope(MASTER);
		result.putArray("events");
		return result;
	}
	
	private ObjectNode page(long sequence) {
		ObjectNode result = envelope(MASTER);
		result.putArray("events").add(envelope(MASTER).put("entitySequence", sequence));
		return result;
	}
	
	@Test
	public void sendsOwnEventsAndReceivesOtherOriginsWithoutDownloadingItself() throws Exception {
		prepare(1, 0);
		when(records.getEncounterEventsAfter(LOCAL, 0, 1)).thenReturn(
		    Collections.singletonList(new EncounterSyncEvent(LOCAL, 1, "evento", "paciente", "json-original")));
		when(remote.receive("json-original")).thenReturn(envelope(LOCAL).put("confirmedSequence", 1));
		when(remote.get(events(0))).thenReturn(page(1));
		when(receiver.receiveEncounter(anyString())).thenReturn(1L);
		when(remote.get(events(1))).thenReturn(emptyPage());
		assertArrayEquals(new int[] { 1, 1 }, client.synchronizeOnce());
		verify(receiver).receiveEncounter(page(1).path("events").get(0).toString());
		verify(receiver, never()).getConfirmedEncounterSequence(LOCAL);
	}
	
	@Test public void lostAcknowledgementResumesFromMastersPersistedConfirmation() throws Exception {
        prepare(1, 0);
        when(records.getEncounterEventsAfter(LOCAL, 0, 1)).thenReturn(Collections.singletonList(
                new EncounterSyncEvent(LOCAL, 1, "evento", "paciente", "json-original")));
        when(remote.receive("json-original")).thenThrow(new IOException("Conexión interrumpida después de guardar"));
        assertThrows(IOException.class, client::synchronizeOnce);
        when(remote.get("resource=status&origin=" + LOCAL)).thenReturn(envelope(LOCAL).put("confirmedSequence", 1));
        assertArrayEquals(new int[] {0, 0}, client.synchronizeOnce());
        verify(remote, times(1)).receive("json-original");
    }
	
	@Test public void failureBeforeDeliveryRetriesTheSameOriginalEvent() throws Exception {
        when(auditService.begin(any())).thenReturn("failed-send","successful-send");
        prepare(1, 0);
        when(records.getEncounterEventsAfter(LOCAL, 0, 1)).thenReturn(Collections.singletonList(
                new EncounterSyncEvent(LOCAL, 1, "evento", "paciente", "json-original")));
        when(remote.receive("json-original")).thenThrow(new IOException("Sin conexión"))
                .thenReturn(envelope(LOCAL).put("confirmedSequence", 1));
        assertThrows(IOException.class, client::synchronizeOnce);
        assertArrayEquals(new int[] {1, 0}, client.synchronizeOnce());
        verify(remote, times(2)).receive("json-original");
        verify(auditService).finish("failed-send","UNCONFIRMED","COMMUNICATION");
        verify(auditService).finish("successful-send","CONFIRMED","OK");
        verify(auditService,times(2)).begin(any());
    }
	
	@Test public void receptionResumesFromLocallyCommittedSequence() throws Exception {
        prepare(0, 0);
        when(remote.get(events(0))).thenReturn(page(1));
        when(receiver.receiveEncounter(anyString())).thenReturn(1L);
        when(remote.get(events(1))).thenThrow(new IOException("Se cortó al pedir la segunda página"));
        assertThrows(IOException.class, client::synchronizeOnce);
        when(receiver.getConfirmedEncounterSequence(MASTER)).thenReturn(1L);
        doReturn(emptyPage()).when(remote).get(events(1));
        assertArrayEquals(new int[] {0, 0}, client.synchronizeOnce());
        verify(receiver, times(1)).receiveEncounter(anyString());
    }
	
	@Test public void rejectsGapBeforeSaving() throws Exception {
        prepare(0, 0);
        when(remote.get(events(0))).thenReturn(page(2));
        assertThrows(IOException.class, client::synchronizeOnce);
        verify(receiver, never()).receiveEncounter(anyString());
    }
	
	@Test public void rejectsWrongMasterBeforeSendingClinicalData() throws Exception {
        prepare(1, 0);
        when(remote.get("resource=node")).thenReturn(mapper.createObjectNode().put("serverId", LOCAL).put("role", "MASTER"));
        assertThrows(IOException.class, client::synchronizeOnce);
        verify(remote, never()).receive(anyString());
    }
	
	@Test public void rejectsConfirmationForAnotherOrigin() throws Exception {
        prepare(1, 0);
        when(records.getEncounterEventsAfter(LOCAL, 0, 1)).thenReturn(Collections.singletonList(
                new EncounterSyncEvent(LOCAL, 1, "evento", "paciente", "json")));
        when(remote.receive("json")).thenReturn(envelope(MASTER).put("confirmedSequence", 1));
        assertThrows(IOException.class, client::synchronizeOnce);
    }
	
	@Test public void rejectsOuterTransactionBeforeNetwork() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, client::synchronizeOnce);
            verifyNoInteractions(remote);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
	
	@Test public void transportRejectsInsecureOrEmbeddedCredentials() {
        assertThrows(IllegalArgumentException.class, () -> new PatientHttpsTransport("http://localhost/sync", "posta", "clave"));
        assertThrows(IllegalArgumentException.class, () -> new PatientHttpsTransport("https://user:secret@localhost/sync", "posta", "clave"));
        assertThrows(IllegalArgumentException.class, () -> new PatientHttpsTransport("https://localhost/sync?x=1", "posta", "clave"));
    }
	
	@Test public void connectionFailureBeforeExchangeDoesNotCreateAuditRows() throws Exception {
        prepare(1,0);
        when(remote.get("resource=node")).thenThrow(new IOException("sin conexi�n"));
        assertThrows(IOException.class,client::synchronizeOnce);
        verifyNoInteractions(auditService);
        verify(remote,never()).receive(anyString());
    }
	
	@Test
	public void repeatedIdleCyclesDoNotCreateAuditRows() throws Exception {
		prepare(0, 0);
		for (int i = 0; i < 3; i++) {
			assertArrayEquals(new int[] { 0, 0 }, client.synchronizeOnce());
		}
		verifyNoInteractions(auditService);
	}
	
	@Test
	public void configuredLimitStopsBothDirectionsAndResumesNextCycle() throws Exception {
		prepare(2, 0);
		client.withLimits(new SyncCycleLimits(1, 100));
		when(records.getEncounterEventsAfter(LOCAL, 0, 1)).thenReturn(
		    Collections.singletonList(new EncounterSyncEvent(LOCAL, 1, "event-one", "patient", "first-payload")));
		when(remote.receive("first-payload")).thenReturn(envelope(LOCAL).put("confirmedSequence", 1));
		when(remote.get(events(0))).thenReturn(page(1));
		when(receiver.receiveEncounter(anyString())).thenReturn(1L);
		assertArrayEquals(new int[] { 1, 1 }, client.synchronizeOnce());
		verify(records, never()).getEncounterEventsAfter(LOCAL, 1, 1);
		verify(remote, never()).get(events(1));
		when(remote.get("resource=status&origin=" + LOCAL)).thenReturn(envelope(LOCAL).put("confirmedSequence", 1));
		when(records.getEncounterEventsAfter(LOCAL, 1, 1)).thenReturn(
		    Collections.singletonList(new EncounterSyncEvent(LOCAL, 2, "event-two", "patient", "second-payload")));
		when(remote.receive("second-payload")).thenReturn(envelope(LOCAL).put("confirmedSequence", 2));
		when(receiver.getConfirmedEncounterSequence(MASTER)).thenReturn(1L);
		when(remote.get(events(1))).thenReturn(page(2));
		when(receiver.receiveEncounter(anyString())).thenReturn(2L);
		assertArrayEquals(new int[] { 1, 1 }, client.synchronizeOnce());
		verify(remote, times(1)).receive("first-payload");
		verify(remote, times(1)).receive("second-payload");
	}
	
	@Test
	public void configuredOriginPagesContinuePastLocalOrigin() throws Exception {
		prepare(0, 0);
		client.withLimits(new SyncCycleLimits(1, 1));
		ObjectNode first = mapper.createObjectNode();
		first.putArray("origins").add(LOCAL);
		ObjectNode second = mapper.createObjectNode();
		second.putArray("origins").add(MASTER);
		ObjectNode last = mapper.createObjectNode();
		last.putArray("origins");
		when(remote.get("resource=origins&limit=1")).thenReturn(first);
		when(remote.get("resource=origins&limit=1&afterOrigin=" + LOCAL)).thenReturn(second);
		when(remote.get("resource=origins&limit=1&afterOrigin=" + MASTER)).thenReturn(last);
		assertArrayEquals(new int[] { 0, 0 }, client.synchronizeOnce());
		verify(remote).get(events(0));
		verify(remote).get("resource=origins&limit=1&afterOrigin=" + MASTER);
	}
}

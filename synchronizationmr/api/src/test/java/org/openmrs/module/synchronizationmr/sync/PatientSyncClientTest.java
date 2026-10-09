package org.openmrs.module.synchronizationmr.sync;

import java.io.IOException;
import java.util.Collections;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openmrs.module.synchronizationmr.api.LocalNodeService;
import org.openmrs.module.synchronizationmr.api.PatientReceiveService;
import org.openmrs.module.synchronizationmr.api.PatientSyncService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pruebas del ciclo y de su reanudación, con servicios y red simulados. */
public class PatientSyncClientTest {
	
	private static final String LOCAL = "testServer_1";
	
	private static final String MASTER = "testServer_2";
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private final LocalNodeService node = mock(LocalNodeService.class);
	
	private final PatientSyncService records = mock(PatientSyncService.class);
	
	private final PatientReceiveService receiver = mock(PatientReceiveService.class);
	
	private final PatientRemoteTransport remote = mock(PatientRemoteTransport.class);
	
	private final org.openmrs.module.synchronizationmr.api.SyncAuditService auditService = mock(org.openmrs.module.synchronizationmr.api.SyncAuditService.class);

	private final PatientSyncClient client = new PatientSyncClient(node, records, receiver, remote, MASTER, new SyncAudit(
	        auditService));
	
	private ObjectNode envelope(String origin) {
		return mapper.createObjectNode().put("originServerId", origin).put("entityType", "PATIENT");
	}
	
	private void prepare(long highest, long confirmed) throws Exception {
		when(node.getLocalServerId()).thenReturn(LOCAL);
		when(remote.get("resource=node")).thenReturn(
		    mapper.createObjectNode().put("serverId", MASTER).put("role", "MASTER").put("protocolVersion", 2));
		when(remote.get("resource=status&origin=" + LOCAL)).thenReturn(envelope(LOCAL).put("confirmedSequence", confirmed));
		when(records.getHighestPatientSequence(LOCAL)).thenReturn(highest);
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
	public void rejectsMasterWithSameServerIdBeforeExchangingPatients() throws Exception {
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
	
	@Test
	public void failedUpdateDoesNotPreventFetchingItsCreationFromAnotherOrigin() throws Exception {
		prepare(0, 0);
		ObjectNode origins = mapper.createObjectNode();
		origins.putArray("origins").add("a_edits").add("z_creator");
		when(remote.get("resource=origins&limit=100")).thenReturn(origins);
		ObjectNode update = envelope("a_edits").put("entitySequence", 1).put("operation", "UPDATE");
		ObjectNode creation = envelope("z_creator").put("entitySequence", 1).put("operation", "CREATE");
		ObjectNode updates = envelope("a_edits"); updates.putArray("events").add(update);
		ObjectNode creations = envelope("z_creator"); creations.putArray("events").add(creation);
		ObjectNode empty = envelope("z_creator"); empty.putArray("events");
		when(remote.get("resource=events&origin=a_edits&after=0&limit=1")).thenReturn(updates);
		when(remote.get("resource=events&origin=z_creator&after=0&limit=1")).thenReturn(creations);
		when(remote.get("resource=events&origin=z_creator&after=1&limit=1")).thenReturn(empty);
		when(receiver.receivePatient(update.toString())).thenThrow(new org.openmrs.api.APIException("Missing patient"));
		when(receiver.receivePatient(creation.toString())).thenReturn(1L);
		assertThrows(org.openmrs.api.APIException.class, client::synchronizeOnce);
		verify(receiver).receivePatient(creation.toString());
		doReturn(1L).when(receiver).receivePatient(update.toString());
		ObjectNode noUpdates = envelope("a_edits"); noUpdates.putArray("events");
		when(remote.get("resource=events&origin=a_edits&after=1&limit=1")).thenReturn(noUpdates);
		when(receiver.getConfirmedPatientSequence("z_creator")).thenReturn(1L);
		assertArrayEquals(new int[] {0, 1}, client.synchronizeOnce());
	}
	
	private ObjectNode page(long sequence) {
		ObjectNode result = envelope(MASTER);
		result.putArray("events").add(envelope(MASTER).put("entitySequence", sequence));
		return result;
	}
	
	@Test
	public void sendsOwnEventsAndReceivesOtherOriginsWithoutDownloadingItself() throws Exception {
		prepare(1, 0);
		when(records.getPatientEventsAfter(LOCAL, 0, 1)).thenReturn(
		    Collections.singletonList(new PatientSyncEvent(LOCAL, 1, "evento", "paciente", "json-original")));
		when(remote.receive("json-original")).thenReturn(envelope(LOCAL).put("confirmedSequence", 1));
		when(remote.get(events(0))).thenReturn(page(1));
		when(receiver.receivePatient(anyString())).thenReturn(1L);
		when(remote.get(events(1))).thenReturn(emptyPage());
		assertArrayEquals(new int[] { 1, 1 }, client.synchronizeOnce());
		verify(receiver).receivePatient(page(1).path("events").get(0).toString());
		verify(receiver, never()).getConfirmedPatientSequence(LOCAL);
	}
	
	@Test public void lostAcknowledgementResumesFromMastersPersistedConfirmation() throws Exception {
        prepare(1, 0);
        when(records.getPatientEventsAfter(LOCAL, 0, 1)).thenReturn(Collections.singletonList(
                new PatientSyncEvent(LOCAL, 1, "evento", "paciente", "json-original")));
        when(remote.receive("json-original")).thenThrow(new IOException("Conexión interrumpida después de guardar"));
        assertThrows(IOException.class, client::synchronizeOnce);
        when(remote.get("resource=status&origin=" + LOCAL)).thenReturn(envelope(LOCAL).put("confirmedSequence", 1));
        assertArrayEquals(new int[] {0, 0}, client.synchronizeOnce());
        verify(remote, times(1)).receive("json-original");
    }
	
	@Test public void failureBeforeDeliveryRetriesTheSameOriginalEvent() throws Exception {
        prepare(1, 0);
        when(records.getPatientEventsAfter(LOCAL, 0, 1)).thenReturn(Collections.singletonList(
                new PatientSyncEvent(LOCAL, 1, "evento", "paciente", "json-original")));
        when(remote.receive("json-original")).thenThrow(new IOException("Sin conexión"))
                .thenReturn(envelope(LOCAL).put("confirmedSequence", 1));
        assertThrows(IOException.class, client::synchronizeOnce);
        assertArrayEquals(new int[] {1, 0}, client.synchronizeOnce());
        verify(remote, times(2)).receive("json-original");
    }
	
	@Test public void receptionResumesFromLocallyCommittedSequence() throws Exception {
        prepare(0, 0);
        when(remote.get(events(0))).thenReturn(page(1));
        when(receiver.receivePatient(anyString())).thenReturn(1L);
        when(remote.get(events(1))).thenThrow(new IOException("Se cortó al pedir la segunda página"));
        assertThrows(IOException.class, client::synchronizeOnce);
        when(receiver.getConfirmedPatientSequence(MASTER)).thenReturn(1L);
        doReturn(emptyPage()).when(remote).get(events(1));
        assertArrayEquals(new int[] {0, 0}, client.synchronizeOnce());
        verify(receiver, times(1)).receivePatient(anyString());
    }
	
	@Test public void rejectsGapBeforeSaving() throws Exception {
        prepare(0, 0);
        when(remote.get(events(0))).thenReturn(page(2));
        assertThrows(IOException.class, client::synchronizeOnce);
        verify(receiver, never()).receivePatient(anyString());
    }
	
	@Test public void rejectsWrongMasterBeforeSendingClinicalData() throws Exception {
        prepare(1, 0);
        when(remote.get("resource=node")).thenReturn(mapper.createObjectNode().put("serverId", LOCAL).put("role", "MASTER"));
        assertThrows(IOException.class, client::synchronizeOnce);
        verify(remote, never()).receive(anyString());
    }
	
	@Test public void rejectsConfirmationForAnotherOrigin() throws Exception {
        prepare(1, 0);
        when(records.getPatientEventsAfter(LOCAL, 0, 1)).thenReturn(Collections.singletonList(
                new PatientSyncEvent(LOCAL, 1, "evento", "paciente", "json")));
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

	@Test public void auditsConnectionFailureWithoutInventingAnEvent() throws Exception {
        prepare(1,0);
        when(auditService.begin(any())).thenReturn("connection");
        when(remote.get("resource=node")).thenThrow(new IOException("sin conexión"));
        assertThrows(IOException.class,client::synchronizeOnce);
        org.mockito.ArgumentCaptor<SyncAuditRecord> capture=org.mockito.ArgumentCaptor.forClass(SyncAuditRecord.class);
        verify(auditService).begin(capture.capture());
        assertEquals("QUERY_NODE",capture.getValue().action);
        assertNull(capture.getValue().eventUuid);
        verify(auditService).finish("connection","FAILED","COMMUNICATION");
        verify(remote,never()).receive(anyString());
    }
}

/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.web;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.module.synchronizationmr.api.*;
import org.openmrs.module.synchronizationmr.sync.EncounterSyncEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Prueba del contrato HTTP con peticiones simuladas; no inicia un servidor. */
public class EncounterSyncHttpServletTest {
	
	@Test
	public void receivesVisitMetadataWithAuthenticatedOrigin() throws Exception {
		org.openmrs.Visit visit = new org.openmrs.Visit();
		visit.setPatient(new org.openmrs.Patient());
		visit.setVisitType(new org.openmrs.VisitType("Prueba", "Ficticia"));
		visit.setStartDatetime(new Date(0));
		String json = org.openmrs.module.synchronizationmr.sync.VisitUpdateEvent.createFull(visit, UUID.randomUUID()
		        .toString(), origin, 1, java.time.Instant.EPOCH);
		when(receiver.receiveEncounter(json)).thenReturn(1L);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		assertEquals(200, call(request).getStatus());
		verify(peer).authorizeEncounterReceive(origin);
		verify(receiver).receiveEncounter(json);
	}
	
	@Test
	public void receivesVisitAnnulmentWithAuthenticatedOrigin() throws Exception {
		org.openmrs.Visit visit = new org.openmrs.Visit();
		visit.setPatient(new org.openmrs.Patient());
		visit.setVoidReason("Prueba de anulación");
		visit.setDateVoided(new Date(0));
		String json = org.openmrs.module.synchronizationmr.sync.VisitVoidEvent.create(visit, UUID.randomUUID().toString(),
		    origin, 1, java.time.Instant.EPOCH);
		when(receiver.receiveEncounter(json)).thenReturn(1L);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		assertEquals(200, call(request).getStatus());
		verify(peer).authorizeEncounterReceive(origin);
		verify(receiver).receiveEncounter(json);
	}
	
	@Test
	public void receivesVisitIntervalWithAuthenticatedOrigin() throws Exception {
		org.openmrs.Visit visit = new org.openmrs.Visit();
		visit.setPatient(new org.openmrs.Patient());
		visit.setStartDatetime(new Date(0));
		visit.setStopDatetime(new Date(60000));
		String json = org.openmrs.module.synchronizationmr.sync.VisitUpdateEvent.create(visit, UUID.randomUUID().toString(),
		    origin, 1, java.time.Instant.EPOCH);
		when(receiver.receiveEncounter(json)).thenReturn(1L);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		assertEquals(200, call(request).getStatus());
		verify(peer).authorizeEncounterReceive(origin);
		verify(receiver).receiveEncounter(json);
	}
	
	@Test
	public void receivesEncounterVoidWithAuthenticatedOrigin() throws Exception {
		org.openmrs.Encounter e = new org.openmrs.Encounter();
		e.setPatient(new org.openmrs.Patient());
		e.setVoidReason("Prueba de anulacion");
		e.setDateVoided(new Date(0));
		String json = org.openmrs.module.synchronizationmr.sync.EncounterVoidEvent.create(e, origin, 1,
		    java.time.Instant.EPOCH);
		when(receiver.receiveEncounter(json)).thenReturn(1L);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		assertEquals(200, call(request).getStatus());
		verify(peer).authorizeEncounterReceive(origin);
		verify(receiver).receiveEncounter(json);
	}
	
	@Test
	public void receivesObservationVoidWithAuthenticatedOrigin() throws Exception {
		org.openmrs.Encounter e = new org.openmrs.Encounter();
		e.setPatient(new org.openmrs.Patient());
		com.fasterxml.jackson.databind.node.ArrayNode items = new ObjectMapper().createArrayNode();
		com.fasterxml.jackson.databind.node.ObjectNode item = items.addObject();
		item.put("uuid", UUID.randomUUID().toString());
		item.put("reason", "Correccion ficticia");
		item.put("dateVoided", "2026-01-01T00:00:00Z");
		String json = org.openmrs.module.synchronizationmr.sync.ObservationVoidEvent.create(e, items, origin, 1,
		    java.time.Instant.parse("2026-01-01T00:00:00Z"));
		when(receiver.receiveEncounter(json)).thenReturn(1L);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		assertEquals(200, call(request).getStatus());
		verify(peer).authorizeEncounterReceive(origin);
		verify(receiver).receiveEncounter(json);
	}
	
	@Test
	public void receivesLegacyVisitSupplementUsingOriginalOrigin() throws Exception {
		org.openmrs.Encounter e = new org.openmrs.Encounter();
		e.setPatient(new org.openmrs.Patient());
		e.setEncounterType(new org.openmrs.EncounterType());
		e.setEncounterDatetime(new Date(0));
		org.openmrs.Visit visit = new org.openmrs.Visit();
		visit.setPatient(e.getPatient());
		visit.setVisitType(new org.openmrs.VisitType());
		visit.setStartDatetime(new Date(0));
		e.setVisit(visit);
		org.openmrs.VisitAttributeType type = new org.openmrs.VisitAttributeType();
		type.setDatatypeClassname("org.openmrs.customdatatype.datatype.FreeTextDatatype");
		org.openmrs.VisitAttribute attribute = new org.openmrs.VisitAttribute();
		attribute.setAttributeType(type);
		attribute.setValueReferenceInternal("Prueba");
		visit.addAttribute(attribute);
		ObjectMapper mapper = new ObjectMapper();
		com.fasterxml.jackson.databind.node.ObjectNode old = (com.fasterxml.jackson.databind.node.ObjectNode) mapper
		        .readTree(new org.openmrs.module.synchronizationmr.sync.EncounterCreationPayloadSerializer().serialize(e,
		            origin, 1, UUID.randomUUID().toString(), new Date(0)));
		old.put("schemaVersion", 3);
		com.fasterxml.jackson.databind.node.ObjectNode data = (com.fasterxml.jackson.databind.node.ObjectNode) old.path(
		    "payload").path("visit");
		data.remove("attributes");
		data.put("unsupportedAttributes", true);
		String wire = org.openmrs.module.synchronizationmr.sync.EncounterVisitSupplement.prepare(old.toString(), visit);
		when(receiver.receiveEncounter(wire)).thenReturn(1L);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(wire.getBytes(StandardCharsets.UTF_8));
		assertEquals(200, call(request).getStatus());
		verify(peer).authorizeEncounterReceive(origin);
		verify(receiver).receiveEncounter(wire);
	}
	
	@Test
	public void receivesCorrectionUsingTheSameAuthenticatedEncounterStream() throws Exception {
		org.openmrs.Encounter encounter = new org.openmrs.Encounter();
		encounter.setPatient(new org.openmrs.Patient());
		org.openmrs.Obs old = new org.openmrs.Obs(), value = new org.openmrs.Obs();
		value.setConcept(new org.openmrs.Concept());
		value.setPreviousVersion(old);
		value.setValueNumeric(77.0);
		value.setObsDatetime(new Date(0));
		com.fasterxml.jackson.databind.node.ObjectNode item = new org.openmrs.module.synchronizationmr.sync.EncounterCreationPayloadSerializer()
		        .observationVersion(value);
		item.put("rootUuid", old.getUuid());
		String json = org.openmrs.module.synchronizationmr.sync.ObservationCorrectionEvent.create(encounter,
		    new ObjectMapper().createArrayNode().add(item), Collections.singletonMap(old.getUuid(), value.getUuid()),
		    origin, 1, java.time.Instant.EPOCH);
		when(receiver.receiveEncounter(json)).thenReturn(1L);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		assertEquals(200, call(request).getStatus());
		verify(peer).authorizeEncounterReceive(origin);
		verify(receiver).receiveEncounter(json);
	}
	
	private final EncounterSyncService sync = mock(EncounterSyncService.class);
	
	private final EncounterReceiveService receiver = mock(EncounterReceiveService.class);
	
	private final LocalNodeService node = mock(LocalNodeService.class);
	
	private final PatientSyncPeerSession peer = mock(PatientSyncPeerSession.class);
	
	private final String origin = "testServer_1";
	
	private final SyncAuditService auditService = mock(SyncAuditService.class);

	private boolean authenticationFails;
	
	private final EncounterSyncHttpServlet servlet = new EncounterSyncHttpServlet() {
		
		@Override
		protected org.openmrs.module.synchronizationmr.sync.SyncAudit audit() {
			when(peer.getPeerServerId()).thenReturn("posta_test");
			return new org.openmrs.module.synchronizationmr.sync.SyncAudit(auditService);
		}

		@Override
		protected PatientSyncPeerSession openPeer(String user, String password) {
			assertEquals("posta", user);
			assertEquals("clave:prueba", password);
			if (authenticationFails) {
				throw new APIAuthenticationException("Credenciales no vÃ¡lidas");
			}
			return peer;
		}
		
		@Override
		protected EncounterSyncService encounters() {
			return sync;
		}
		
		@Override
		protected EncounterReceiveService encounterReceiver() {
			return receiver;
		}
		
		@Override
		protected LocalNodeService node() {
			return node;
		}
	};
	
	@BeforeEach
	public void auditIdentity() {
		when(node.getLocalServerId()).thenReturn("maestro");
		when(peer.getPeerServerId()).thenReturn("posta_test");
	}

	private MockHttpServletRequest request(String method, String resource) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/moduleServlet/synchronizationmr/encounterSync");
		request.setSecure(true);
		request.addHeader("Authorization",
		    "Basic " + Base64.getEncoder().encodeToString("posta:clave:prueba".getBytes(StandardCharsets.UTF_8)));
		request.setParameter("resource", resource);
		return request;
	}
	
	private MockHttpServletResponse call(MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		servlet.service(request, response);
		return response;
	}
	
	private String event() {
		return "{\"schemaVersion\":2,\"originServerId\":\"" + origin + "\",\"eventUuid\":\"" + UUID.randomUUID()
		        + "\",\"entitySequence\":1,\"entityType\":\"ENCOUNTER\",\"operation\":\"CREATE\","
		        + "\"occurredAt\":\"2026-09-20T00:00:00Z\",\"payload\":{\"encounterUuid\":\"" + UUID.randomUUID()
		        + "\",\"patientUuid\":\"" + UUID.randomUUID() + "\"}}";
	}
	
	@AfterEach
	public void cleanup() {
		TransactionSynchronizationManager.clear();
	}
	
	@Test
	public void receivesUpdateThroughAuthenticatedEndpointAndRejectsUnsupportedGroups() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		com.fasterxml.jackson.databind.node.ObjectNode update = (com.fasterxml.jackson.databind.node.ObjectNode) mapper
		        .readTree(event());
		update.put("schemaVersion", 5);
		update.put("operation", "UPDATE");
		update.putArray("changedGroups").add("locationUuid");
		com.fasterxml.jackson.databind.node.ObjectNode data = (com.fasterxml.jackson.databind.node.ObjectNode) update
		        .get("payload");
		data.put("encounterDatetime", "2026-01-01T00:00:00Z");
		data.put("encounterTypeUuid", "tipo");
		data.putNull("locationUuid");
		data.putNull("formUuid");
		data.putArray("encounterProviders");
		String json = update.toString();
		when(receiver.receiveEncounter(json)).thenReturn(1L);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		assertEquals(200, call(request).getStatus());
		verify(peer).authorizeEncounterReceive(origin);
		verify(receiver).receiveEncounter(json);
		update.putArray("changedGroups").add("obs");
		request.setContent(update.toString().getBytes(StandardCharsets.UTF_8));
		assertEquals(400, call(request).getStatus());
		verifyNoMoreInteractions(receiver);
	}
	
	@Test
	public void rejectsPlainHttpBeforeAuthentication() throws Exception {
		MockHttpServletRequest request = request("GET", "node");
		request.setSecure(false);
		assertEquals(403, call(request).getStatus());
		verifyNoInteractions(peer, sync, node);
	}
	
	@Test
	public void rejectsMissingOrBadCredentials() throws Exception {
		MockHttpServletRequest request = request("GET", "node");
		request.removeHeader("Authorization");
		MockHttpServletResponse response = call(request);
		assertEquals(401, response.getStatus());
		assertNotNull(response.getHeader("WWW-Authenticate"));
		authenticationFails = true;
		assertEquals(401, call(request("GET", "node")).getStatus());
		verifyNoInteractions(sync, node);
	}
	
	@Test
	public void rejectsUnconfiguredPeerAndClosesScope() throws Exception {
		doThrow(new APIAuthenticationException("Cuenta sin configurar")).when(peer).authorize();
		assertEquals(403, call(request("GET", "node")).getStatus());
		verify(peer).close();
		verifyNoInteractions(node);
	}
	
	@Test
	public void returnsNodeAndOrigins() throws Exception {
		when(node.getLocalServerId()).thenReturn(origin);
		when(peer.getLocalRole()).thenReturn("MASTER");
		MockHttpServletResponse response = call(request("GET", "node"));
		assertEquals(200, response.getStatus());
		assertTrue(response.getContentAsString().contains(origin));
		assertEquals("testServer_1", new ObjectMapper().readTree(response.getContentAsString()).get("serverId").asText());
		assertEquals("no-store", response.getHeader("Cache-Control"));
		when(sync.getEncounterOrigins(null, 25)).thenReturn(Collections.singletonList(origin));
		assertTrue(call(request("GET", "origins")).getContentAsString().contains(origin));
	}
	
	@Test
	public void rejectsMissingServerIdentityOnEveryResource() throws Exception {
		when(node.getLocalServerId()).thenThrow(new APIException("Configure server.id"));
		for (String resource : Arrays.asList("node", "origins", "status", "events", "receive")) {
			assertEquals(409, call(request("receive".equals(resource) ? "POST" : "GET", resource)).getStatus());
		}
		verifyNoInteractions(sync, receiver);
	}
	
	@Test
	public void separatesHighestFromConfirmedSequence() throws Exception {
		when(sync.getHighestEncounterSequence(origin)).thenReturn(3L);
		when(receiver.getConfirmedEncounterSequence(origin)).thenReturn(1L);
		MockHttpServletRequest request = request("GET", "status");
		request.setParameter("origin", origin);
		JsonNode json = new ObjectMapper().readTree(call(request).getContentAsString());
		assertEquals(3, json.get("highestSequence").asInt());
		assertEquals(1, json.get("confirmedSequence").asInt());
	}
	
	@Test
	public void returnsJsonObjectsAndDoesNotConfirmPages() throws Exception {
		String json = event();
		when(sync.getEncounterEventsAfter(origin, 0, 2)).thenReturn(
		    Collections.singletonList(new EncounterSyncEvent(origin, 1, "evento", "paciente", json)));
		MockHttpServletRequest request = request("GET", "events");
		request.setParameter("origin", origin);
		request.setParameter("limit", "2");
		JsonNode result = new ObjectMapper().readTree(call(request).getContentAsString());
		assertTrue(result.get("events").get(0).isObject());
		assertEquals(1, result.get("lastReturnedSequence").asInt());
		verifyNoInteractions(receiver);
	}
	
	@Test
	public void rejectsInvalidPaginationAndMissingJsonEvent() throws Exception {
		MockHttpServletRequest request = request("GET", "events");
		request.setParameter("origin", origin);
		request.setParameter("limit", "101");
		assertEquals(400, call(request).getStatus());
		request.setParameter("limit", "2");
		when(sync.getEncounterEventsAfter(origin, 0, 2)).thenThrow(new APIException("Datos privados que no deben salir"));
		MockHttpServletResponse response = call(request);
		assertEquals(409, response.getStatus());
		assertFalse(response.getContentAsString().contains("privados"));
	}
	
	@Test
	public void receivesOnlyAuthorizedOriginAndReturnsConfirmation() throws Exception {
		String json = event();
		when(receiver.receiveEncounter(json)).thenReturn(1L);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = call(request);
		assertEquals(200, response.getStatus());
		verify(peer).authorizeEncounterReceive(origin);
		verify(receiver).receiveEncounter(json);
		assertEquals(1, new ObjectMapper().readTree(response.getContentAsString()).get("confirmedSequence").asInt());
	}
	
	@Test
	public void forbidsOriginSpoofingBeforeCallingReceiver() throws Exception {
		doThrow(new APIAuthenticationException("Origen ajeno")).when(peer).authorizeEncounterReceive(origin);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(event().getBytes(StandardCharsets.UTF_8));
		assertEquals(403, call(request).getStatus());
		verifyNoInteractions(receiver);
	}
	
	@Test
	public void rejectsOuterTransactionBeforeAcknowledging() throws Exception {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(event().getBytes(StandardCharsets.UTF_8));
		assertEquals(503, call(request).getStatus());
		verifyNoInteractions(receiver);
	}
	
	@Test
	public void rejectsUnsupportedBodiesAndMethods() throws Exception {
		assertEquals(405, call(request("DELETE", "receive")).getStatus());
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("text/plain");
		assertEquals(415, call(request).getStatus());
		request.setContentType("application/json");
		request.setContent("{}".getBytes(StandardCharsets.UTF_8));
		assertEquals(400, call(request).getStatus());
		request.setContent(new byte[1048577]);
		assertEquals(413, call(request).getStatus());
		verifyNoInteractions(receiver);
	}
}

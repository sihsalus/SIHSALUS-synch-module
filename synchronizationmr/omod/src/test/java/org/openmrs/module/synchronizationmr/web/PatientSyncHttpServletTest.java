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
import org.openmrs.module.synchronizationmr.sync.PatientSyncEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Prueba del contrato HTTP con peticiones simuladas; no inicia un servidor. */
public class PatientSyncHttpServletTest {
	
	private final PatientSyncService sync = mock(PatientSyncService.class);
	
	private final PatientReceiveService receiver = mock(PatientReceiveService.class);
	
	private final LocalNodeService node = mock(LocalNodeService.class);
	
	private final PatientSyncPeerSession peer = mock(PatientSyncPeerSession.class);
	
	private final String origin = "testServer_1";
	
	private boolean authenticationFails;
	
	private final PatientSyncHttpServlet servlet = new PatientSyncHttpServlet() {
		
		@Override
		protected PatientSyncPeerSession openPeer(String user, String password) {
			assertEquals("posta", user);
			assertEquals("clave:prueba", password);
			if (authenticationFails) {
				throw new APIAuthenticationException("Credenciales no válidas");
			}
			return peer;
		}
		
		@Override
		protected PatientSyncService sync() {
			return sync;
		}
		
		@Override
		protected PatientReceiveService receiver() {
			return receiver;
		}
		
		@Override
		protected LocalNodeService node() {
			return node;
		}
	};
	
	private MockHttpServletRequest request(String method, String resource) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/moduleServlet/synchronizationmr/patientSync");
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
		return "{\"schemaVersion\":3,\"originServerId\":\"" + origin + "\",\"eventUuid\":\"" + UUID.randomUUID()
		        + "\",\"entityType\":\"PATIENT\",\"entitySequence\":1,\"operation\":\"CREATE\","
		        + "\"occurredAt\":\"2026-09-14T00:00:00Z\",\"payload\":{\"patientUuid\":\"" + UUID.randomUUID()
		        + "\",\"addresses\":[]}}";
	}
	
	@AfterEach
	public void cleanup() {
		TransactionSynchronizationManager.clear();
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
		when(sync.getPatientOrigins(null, 25)).thenReturn(Collections.singletonList(origin));
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
		when(sync.getHighestPatientSequence(origin)).thenReturn(3L);
		when(receiver.getConfirmedPatientSequence(origin)).thenReturn(1L);
		MockHttpServletRequest request = request("GET", "status");
		request.setParameter("origin", origin);
		JsonNode json = new ObjectMapper().readTree(call(request).getContentAsString());
		assertEquals(3, json.get("highestSequence").asInt());
		assertEquals(1, json.get("confirmedSequence").asInt());
	}
	
	@Test
	public void returnsJsonObjectsAndDoesNotConfirmPages() throws Exception {
		String json = event();
		when(sync.getPatientEventsAfter(origin, 0, 2)).thenReturn(
		    Collections.singletonList(new PatientSyncEvent(origin, 1, "evento", "paciente", json)));
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
		when(sync.getPatientEventsAfter(origin, 0, 2)).thenThrow(new APIException("Datos privados que no deben salir"));
		MockHttpServletResponse response = call(request);
		assertEquals(409, response.getStatus());
		assertFalse(response.getContentAsString().contains("privados"));
	}
	
	@Test
	public void receivesOnlyAuthorizedOriginAndReturnsConfirmation() throws Exception {
		String json = event();
		when(receiver.receivePatient(json)).thenReturn(1L);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = call(request);
		assertEquals(200, response.getStatus());
		verify(peer).authorizeReceive(origin);
		verify(receiver).receivePatient(json);
		assertEquals(1, new ObjectMapper().readTree(response.getContentAsString()).get("confirmedSequence").asInt());
	}
	
	@Test
	public void forbidsOriginSpoofingBeforeCallingReceiver() throws Exception {
		doThrow(new APIAuthenticationException("Origen ajeno")).when(peer).authorizeReceive(origin);
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

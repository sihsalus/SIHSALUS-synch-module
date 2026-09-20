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
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.*;
import org.openmrs.api.context.*;
import org.openmrs.module.synchronizationmr.api.*;
import org.openmrs.module.synchronizationmr.sync.PatientCreationPayloadSerializer;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

/** Peticiones servlet simuladas con autenticación y servicios reales de OpenMRS. */
public class PatientSyncHttpIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private final PatientSyncHttpServlet servlet = new PatientSyncHttpServlet();
	
	private String peerServerId;
	
	@BeforeEach
	public void preparePeer() throws Exception {
		new Liquibase("../api/src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("synchronizationmr.nodeRole", "MASTER"));
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		peerServerId = "testServer_2";
		User user = Context.getAuthenticatedUser();
		user.setUserProperty("synchronizationmr.peerServerId", peerServerId);
		user.setUserProperty("synchronizationmr.peerRole", "POSTA");
		Context.getUserService().saveUser(user);
	}
	
	private MockHttpServletRequest request(String method, String resource) {
		UsernamePasswordCredentials credentials = (UsernamePasswordCredentials) getCredentials();
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/moduleServlet/synchronizationmr/patientSync");
		request.setSecure(true);
		request.setParameter("resource", resource);
		String pair = credentials.getUsername() + ":" + credentials.getPassword();
		request.addHeader("Authorization",
		    "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
		return request;
	}
	
	private MockHttpServletResponse call(MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		servlet.service(request, response);
		return response;
	}
	
	private String event(String origin, Patient patient) {
		patient.setGender("F");
		patient.addName(new PersonName("Paciente", null, "HTTP ficticio"));
		PatientIdentifier identifier = new PatientIdentifier("HTTP-" + UUID.randomUUID(), Context.getPatientService()
		        .getPatientIdentifierType(2), Context.getLocationService().getLocation(1));
		identifier.setPreferred(true);
		patient.addIdentifier(identifier);
		return new PatientCreationPayloadSerializer()
		        .serialize(patient, origin, 1, UUID.randomUUID().toString(), new Date());
	}
	
	@Test
	public void authenticatesPerRequestAndRestoresPreviousUserContext() throws Exception {
		UserContext previous = Context.getUserContext();
		MockHttpServletRequest request = request("GET", "node");
		MockHttpServletResponse response = call(request);
		assertEquals(200, response.getStatus(), response.getContentAsString());
		assertSame(previous, Context.getUserContext());
		assertNull(request.getSession(false));
		assertEquals("MASTER", new ObjectMapper().readTree(response.getContentAsString()).get("role").asText());
	}
	
	@Test
	public void wrongCredentialsCannotUseExistingBrowserAuthentication() throws Exception {
		UserContext previous = Context.getUserContext();
		MockHttpServletRequest request = request("GET", "node");
		request.removeHeader("Authorization");
		request.addHeader("Authorization",
		    "Basic " + Base64.getEncoder().encodeToString("usuario-inexistente:error".getBytes(StandardCharsets.UTF_8)));
		assertEquals(401, call(request).getStatus());
		assertSame(previous, Context.getUserContext());
		assertTrue(Context.isAuthenticated());
	}
	
	@Test
	public void postaCannotImpersonateAnotherOrigin() throws Exception {
		Patient patient = new Patient();
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(event(UUID.randomUUID().toString(), patient).getBytes(StandardCharsets.UTF_8));
		TestTransaction.flagForCommit();
		TestTransaction.end();
		try {
			assertEquals(403, call(request).getStatus());
			assertNull(Context.getPatientService().getPatientByUuid(patient.getUuid()));
		}
		finally {
			TestTransaction.start();
		}
	}
	
	@Test
	public void respondsWithConfirmationAfterRealCommitAndAllowsRetry() throws Exception {
		Patient patient = new Patient();
		String json = event(peerServerId, patient);
		MockHttpServletRequest request = request("POST", "receive");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		UserContext previous = Context.getUserContext();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		try {
			MockHttpServletResponse response = call(request);
			assertEquals(200, response.getStatus(), response.getContentAsString());
			assertSame(previous, Context.getUserContext());
			assertEquals(1, new ObjectMapper().readTree(response.getContentAsString()).get("confirmedSequence").asLong());
			assertNotNull(Context.getPatientService().getPatientByUuid(patient.getUuid()));
			assertEquals(1, Context.getService(PatientReceiveService.class).getConfirmedPatientSequence(peerServerId));
			MockHttpServletRequest repeat = request("POST", "receive");
			repeat.setContentType("application/json");
			repeat.setContent(json.getBytes(StandardCharsets.UTF_8));
			assertEquals(200, call(repeat).getStatus());
			System.out.println("HTTP SIMULADO VERIFICADO: autenticación real | paciente guardado | confirmación=1");
		}
		finally {
			TestTransaction.start();
		}
	}
}

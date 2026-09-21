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

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import javax.servlet.http.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.api.*;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transporte JSON del OMOD. Los servicios hacen commit antes de construir la confirmación HTTP. */
public class PatientSyncHttpServlet extends HttpServlet {
	
	private static final int MAX_BODY = 1048576;
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	protected PatientSyncPeerSession openPeer(String user, String password) {
		return new PatientSyncPeerSession(user, password);
	}
	
	protected PatientSyncService sync() {
		return Context.getService(PatientSyncService.class);
	}
	
	protected PatientReceiveService receiver() {
		return Context.getService(PatientReceiveService.class);
	}
	
	protected LocalNodeService node() {
		return Context.getService(LocalNodeService.class);
	}
	
	protected void authorizeReceive(PatientSyncPeerSession peer, String origin) {
		peer.authorizeReceive(origin);
	}
	
	protected String entityType() {
		return "PATIENT";
	}
	
	protected String incomingOrigin(String json) {
		return new PatientIncomingEvent(json).origin;
	}
	
	protected long receiveEvent(String json) {
		return receiver().receivePatient(json);
	}
	
	protected java.util.List<String> origins(String after, int limit) {
		return sync().getPatientOrigins(after, limit);
	}
	
	protected long highest(String origin) {
		return sync().getHighestPatientSequence(origin);
	}
	
	protected long confirmed(String origin) {
		return receiver().getConfirmedPatientSequence(origin);
	}
	
	protected java.util.List<String> payloads(String origin, long after, int limit) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (PatientSyncEvent event : sync().getPatientEventsAfter(origin, after, limit)) { result.add(event.getPayloadJson()); }
        return result;
    }
	
	@Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!request.isSecure()) { error(response, 403, "HTTPS_REQUIRED"); return; }
        if (!"GET".equals(request.getMethod()) && !"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "GET, POST"); error(response, 405, "METHOD_NOT_ALLOWED"); return;
        }
        PatientSyncPeerSession peer;
        try {
            String header = request.getHeader("Authorization");
            if (header == null || header.length() > 8192 || !header.startsWith("Basic ")) { throw new IllegalArgumentException(); }
            String pair = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            int colon = pair.indexOf(':');
            if (colon < 1 || colon == pair.length() - 1) { throw new IllegalArgumentException(); }
            peer = openPeer(pair.substring(0, colon), pair.substring(colon + 1));
        } catch (RuntimeException failure) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"OpenMRS synchronization\", charset=\"UTF-8\"");
            error(response, 401, "AUTHENTICATION_REQUIRED"); return;
        }
        try (PatientSyncPeerSession authenticated = peer) {
            authenticated.authorize();
            // Ninguna operación remota elude la configuración de identidad local.
            String serverId = node().getLocalServerId();
            String resource = request.getParameter("resource");
            ObjectNode result;
            if ("POST".equals(request.getMethod())) {
                if (!"receive".equals(resource)) { error(response, 404, "RESOURCE_NOT_FOUND"); return; }
                String contentType = request.getContentType();
                if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
                    error(response, 415, "JSON_REQUIRED"); return;
                }
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    error(response, 503, "OUTER_TRANSACTION_NOT_SUPPORTED"); return;
                }
                String json = readBody(request);
                String incomingOrigin;
                try { incomingOrigin = incomingOrigin(json); }
                catch (APIException invalid) { error(response, 400, "INVALID_EVENT"); return; }
                authorizeReceive(authenticated, incomingOrigin);
                long confirmed = receiveEvent(json);
                result = mapper.createObjectNode();
                result.put("originServerId", incomingOrigin); result.put("entityType", entityType());
                result.put("confirmedSequence", confirmed);
            } else {
                result = get(request, authenticated, serverId);
                if (result == null) { error(response, 404, "RESOURCE_NOT_FOUND"); return; }
            }
            response.setStatus(200);
            mapper.writeValue(response.getWriter(), result);
        } catch (BodyTooLarge failure) { error(response, 413, "BODY_TOO_LARGE"); }
        catch (IllegalArgumentException failure) { error(response, 400, "INVALID_PARAMETERS"); }
        catch (APIAuthenticationException failure) { error(response, 403, "FORBIDDEN"); }
        catch (APIException failure) { error(response, 409, "SYNCHRONIZATION_REJECTED"); }
        catch (RuntimeException failure) { error(response, 500, "INTERNAL_ERROR"); }
    }
	
	protected void enrichStatus(ObjectNode result) {
	}
	
	private ObjectNode get(HttpServletRequest request, PatientSyncPeerSession peer, String serverId) throws IOException {
		String resource = request.getParameter("resource");
		ObjectNode result = mapper.createObjectNode();
		result.put("entityType", entityType());
		if ("node".equals(resource)) {
			result.put("serverId", serverId);
			result.put("role", peer.getLocalRole());
			result.put("protocolVersion", 2);
		} else if ("origins".equals(resource)) {
			String after = request.getParameter("afterOrigin");
			if (after != null) {
				origin(after);
			}
			int limit = limit(request);
			ArrayNode origins = result.putArray("origins");
			for (String uuid : origins(after, limit)) {
				origins.add(uuid);
			}
			result.put("limit", limit);
		} else if ("status".equals(resource) || "events".equals(resource)) {
			String origin = origin(request.getParameter("origin"));
			result.put("originServerId", origin);
			if ("status".equals(resource)) {
				result.put("highestSequence", highest(origin));
				enrichStatus(result);
				result.put("confirmedSequence", confirmed(origin));
			} else {
				long after = number(request, "after", 0);
				int limit = limit(request);
				ArrayNode events = result.putArray("events");
				long last = after;
				for (String payload : payloads(origin, after, limit)) {
					JsonNode event = mapper.readTree(payload);
					events.add(event);
					last = event.path("entitySequence").asLong();
				}
				result.put("afterSequence", after);
				result.put("lastReturnedSequence", last);
				result.put("limit", limit);
			}
		} else {
			return null;
		}
		return result;
	}
	
	private int limit(HttpServletRequest request) {
		long value = number(request, "limit", 25);
		if (value < 1 || value > 100) {
			throw new IllegalArgumentException();
		}
		return (int) value;
	}
	
	private long number(HttpServletRequest request, String name, long fallback) {
		String value = request.getParameter(name);
		long result = value == null ? fallback : Long.parseLong(value);
		if (result < 0) {
			throw new IllegalArgumentException();
		}
		return result;
	}
	
	private String origin(String value) {
		return ServerId.requireValid(value);
	}
	
	private String readBody(HttpServletRequest request) throws IOException {
		if (request.getContentLength() > MAX_BODY) {
			throw new BodyTooLarge();
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int count;
		InputStream input = request.getInputStream();
		while ((count = input.read(buffer)) != -1) {
			if (count > MAX_BODY - bytes.size()) {
				throw new BodyTooLarge();
			}
			bytes.write(buffer, 0, count);
		}
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
			        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
			        .toString();
		}
		catch (CharacterCodingException invalid) {
			throw new IllegalArgumentException();
		}
	}
	
	private void error(HttpServletResponse response, int status, String code) throws IOException {
		response.setStatus(status);
		ObjectNode result = mapper.createObjectNode();
		result.put("error", code);
		mapper.writeValue(response.getWriter(), result);
	}
	
	private static class BodyTooLarge extends IOException {}
}

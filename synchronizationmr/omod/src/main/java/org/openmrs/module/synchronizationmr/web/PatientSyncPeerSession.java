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

import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.api.context.UsernamePasswordCredentials;

/** Autenticación por petición; no cambia la identidad de una sesión web existente. */
public class PatientSyncPeerSession implements AutoCloseable {
	
	private UserContext previous;
	
	private boolean openedSession;
	
	private boolean installedContext;
	
	private String localRole;
	
	private String peerServerId;
	
	public PatientSyncPeerSession(String username, String password) {
		try {
			try {
				previous = Context.getUserContext();
			}
			catch (APIException absentContext) {
				previous = null;
			}
			if (previous == null) {
				Context.openSession();
				openedSession = true;
			} else {
				Context.setUserContext(new UserContext(Context.getAuthenticationScheme()));
				installedContext = true;
			}
			// No se reutiliza autenticación de cookies ni se guardan credenciales en HttpSession.
			Context.authenticate(new UsernamePasswordCredentials(username, password));
		}
		catch (RuntimeException failure) {
			close();
			throw failure;
		}
	}
	
	public void authorize() {
		if (!Context.hasPrivilege("View Synchronization Records") || !Context.hasPrivilege("Get Patients")) {
			throw new APIAuthenticationException("Faltan permisos para consultar sincronización");
		}
		localRole = Context.getAdministrationService().getGlobalProperty("synchronizationmr.nodeRole", "UNCONFIGURED");
		User user = Context.getAuthenticatedUser();
		peerServerId = user.getUserProperty("synchronizationmr.peerServerId");
		String role = user.getUserProperty("synchronizationmr.peerRole");
		if (!org.openmrs.module.synchronizationmr.sync.ServerId.isValid(peerServerId)
		        || !("MASTER".equals(localRole) && "POSTA".equals(role) || "POSTA".equals(localRole)
		                && "MASTER".equals(role))) {
			throw new APIAuthenticationException("La cuenta remota no corresponde a una conexión posta–maestro configurada");
		}
	}
	
	public void authorizeReceive(String origin) {
		if (!Context.hasPrivilege("Receive Synchronization Records") || !Context.hasPrivilege("Add Patients")) {
			throw new APIAuthenticationException("Faltan permisos de recepción");
		}
		if ("MASTER".equals(localRole) && !peerServerId.equals(origin)) {
			throw new APIAuthenticationException("Una posta solo puede enviar eventos de su propio origen");
		}
	}
	
	public String getLocalRole() {
		return localRole;
	}
	
	public String getPeerServerId() {
		return peerServerId;
	}
	
	@Override
	public void close() {
		if (openedSession) {
			openedSession = false;
			Context.closeSession();
		} else if (installedContext) {
			installedContext = false;
			Context.setUserContext(previous);
		}
	}
}

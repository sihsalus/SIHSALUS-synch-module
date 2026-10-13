package org.openmrs.module.synchronizationmr.web;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.openmrs.*;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import static org.junit.jupiter.api.Assertions.*;

/** Permisos e identidad con autenticación real de OpenMRS en la base aislada de pruebas. */
public class PatientSyncPeerSessionIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private static final String PASSWORD = "Test-Peer123!";
	
	private static final String[] PRIVILEGES = { "View Synchronization Records", "Get Patients",
	        "Receive Synchronization Records", "Add Patients", "Add Encounters", "Add Observations", "Add Orders" };
	
	private String account(String peerId, String peerRole, String omitted) {
		String name = "peer" + UUID.randomUUID().toString().substring(0, 8);
		Role role = new Role(name);
		for (String privilege : PRIVILEGES) {
			if (privilege.equals(omitted))
				continue;
			Privilege value = Context.getUserService().getPrivilege(privilege);
			if (value == null)
				value = Context.getUserService().savePrivilege(new Privilege(privilege, "Prueba aislada"));
			role.addPrivilege(value);
		}
		Context.getUserService().saveRole(role);
		User user = new User();
		user.setPerson(Context.getPersonService().getPerson(2));
		user.setUsername(name);
		user.addRole(role);
		user.setUserProperty("synchronizationmr.peerServerId", peerId);
		user.setUserProperty("synchronizationmr.peerRole", peerRole);
		Context.getUserService().createUser(user, PASSWORD);
		return name;
	}
	
	private void localRole(String role) {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("synchronizationmr.nodeRole", role));
	}
	
	@Test public void validTechnicalAccountReceivesOwnOriginAndRestoresWebContext() {
        localRole("MASTER");
        String name = account("posta_a", "POSTA", null);
        UserContext previous = Context.getUserContext();
        Integer previousUser = Context.getAuthenticatedUser().getUserId();
        try (PatientSyncPeerSession peer = new PatientSyncPeerSession(name, PASSWORD)) {
            assertNotSame(previous, Context.getUserContext());
            assertEquals(name, Context.getAuthenticatedUser().getUsername());
            peer.authorize();
            peer.authorizeReceive("posta_a");
            peer.authorizeEncounterReceive("posta_a");
            peer.authorizeOrderReceive("posta_a");
            assertEquals("posta_a", peer.getPeerServerId());
        }
        assertSame(previous, Context.getUserContext());
        assertEquals(previousUser, Context.getAuthenticatedUser().getUserId());
    }
	
	@Test public void wrongPasswordCannotReuseAuthenticatedWebSession() {
        localRole("MASTER");
        String name = account("posta_a", "POSTA", null);
        UserContext previous = Context.getUserContext();
        assertThrows(org.openmrs.api.context.ContextAuthenticationException.class, () -> new PatientSyncPeerSession(name, "incorrecta"));
        assertSame(previous, Context.getUserContext());
    }
	
	@Test public void queryRequiresBothPrivileges() {
        localRole("MASTER");
        for (String omitted : new String[] {"View Synchronization Records", "Get Patients"}) {
            String name = account("posta_a", "POSTA", omitted);
            try (PatientSyncPeerSession peer = new PatientSyncPeerSession(name, PASSWORD)) {
                assertThrows(APIAuthenticationException.class, peer::authorize);
            }
        }
    }
	
	@Test public void receptionRequiresCommonAndEntityPrivileges() {
        localRole("MASTER");
        for (String omitted : new String[] {"Receive Synchronization Records", "Add Patients", "Add Encounters", "Add Observations", "Add Orders"}) {
            String name = account("posta_a", "POSTA", omitted);
            try (PatientSyncPeerSession peer = new PatientSyncPeerSession(name, PASSWORD)) {
                peer.authorize();
                if (omitted.equals("Receive Synchronization Records") || omitted.equals("Add Patients"))
                    assertThrows(APIAuthenticationException.class, () -> peer.authorizeReceive("posta_a"));
                if (omitted.equals("Receive Synchronization Records") || omitted.equals("Add Encounters") || omitted.equals("Add Observations"))
                    assertThrows(APIAuthenticationException.class, () -> peer.authorizeEncounterReceive("posta_a"));
                if (omitted.equals("Receive Synchronization Records") || omitted.equals("Add Orders"))
                    assertThrows(APIAuthenticationException.class, () -> peer.authorizeOrderReceive("posta_a"));
            }
        }
    }
	
	@Test public void masterRejectsPostaClaimingAnotherOriginForEveryFlow() {
        localRole("MASTER");
        String name = account("posta_a", "POSTA", null);
        try (PatientSyncPeerSession peer = new PatientSyncPeerSession(name, PASSWORD)) {
            peer.authorize();
            assertThrows(APIAuthenticationException.class, () -> peer.authorizeReceive("posta_b"));
            assertThrows(APIAuthenticationException.class, () -> peer.authorizeEncounterReceive("posta_b"));
            assertThrows(APIAuthenticationException.class, () -> peer.authorizeOrderReceive("posta_b"));
        }
    }
	
	@Test public void configuredMasterCanForwardAnotherOriginToPosta() {
        localRole("POSTA");
        String name = account("maestro", "MASTER", null);
        try (PatientSyncPeerSession peer = new PatientSyncPeerSession(name, PASSWORD)) {
            peer.authorize();
            peer.authorizeReceive("posta_b");
            peer.authorizeEncounterReceive("posta_b");
            peer.authorizeOrderReceive("posta_b");
        }
    }
	
	@Test public void rejectsInvalidPeerIdentityAndIncompatibleRoles() {
        localRole("MASTER");
        for (String[] properties : new String[][] {{"", "POSTA"}, {"posta_a", "MASTER"}, {"posta_a", "UNCONFIGURED"}}) {
            String name = account(properties[0], properties[1], null);
            try (PatientSyncPeerSession peer = new PatientSyncPeerSession(name, PASSWORD)) {
                assertThrows(APIAuthenticationException.class, peer::authorize);
            }
        }
        localRole("UNCONFIGURED");
        String name = account("posta_a", "POSTA", null);
        try (PatientSyncPeerSession peer = new PatientSyncPeerSession(name, PASSWORD)) {
            assertThrows(APIAuthenticationException.class, peer::authorize);
        }
    }
}

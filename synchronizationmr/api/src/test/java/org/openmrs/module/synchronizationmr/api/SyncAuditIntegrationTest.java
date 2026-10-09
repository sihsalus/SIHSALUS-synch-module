package org.openmrs.module.synchronizationmr.api;

import java.sql.*;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.SyncAuditRecord;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class SyncAuditIntegrationTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
	}

	private SyncAuditService service() {
		return Context.getService(SyncAuditService.class);
	}

	private SyncAuditRecord record() {
		return new SyncAuditRecord("posta_a", "maestro", "ORDER", "SEND", null);
	}

	private String result(String id) throws Exception {
        try (PreparedStatement q=getConnection().prepareStatement("select result from synchronizationmr_audit where attempt_uuid=?")) {
            q.setString(1,id);
            try (ResultSet rows=q.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }

	@Test
	public void survivesRollbackOfOuterTransactionAndPreservesRetryHistory() throws Exception {
		String first = service().begin(record());
		service().finish(first, "UNCONFIRMED", "COMMUNICATION");
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertEquals("UNCONFIRMED", result(first));
		String next = service().begin(record());
		service().finish(next, "CONFIRMED", "OK");
		assertNotEquals(first, next);
		assertEquals("UNCONFIRMED", result(first));
		assertEquals("CONFIRMED", result(next));
	}

	@Test public void cannotOverwriteCompletedAttempt() throws Exception {
        String id=service().begin(record()); service().finish(id,"FAILED","REJECTED");
        assertThrows(APIException.class,() -> service().finish(id,"CONFIRMED","OK"));
        assertEquals("FAILED",result(id));
    }

	@Test
	public void interruptedAttemptRemainsVisible() throws Exception {
		String id = service().begin(record());
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertEquals("IN_PROGRESS", result(id));
	}

	@Test public void rejectsArbitraryExceptionTextAsResultCode() throws Exception {
        String id=service().begin(record());
        assertThrows(IllegalArgumentException.class,() -> service().finish(id,"FAILED","datos privados"));
        assertEquals("IN_PROGRESS",result(id));
    }

	@Test public void requiresAuditPrivilegeAndAllowsTechnicalRole() throws Exception {
        String name="audit"+java.util.UUID.randomUUID().toString().substring(0,8);
        org.openmrs.Privilege privilege=new org.openmrs.Privilege("Record Synchronization Audit","Auditoría");
        Context.getUserService().savePrivilege(privilege);
        org.openmrs.Role role=new org.openmrs.Role(name);
        Context.getUserService().saveRole(role);
        org.openmrs.User user=new org.openmrs.User(); user.setPerson(Context.getPersonService().getPerson(2));
        user.setUsername(name); user.addRole(role);
        String password=java.util.UUID.randomUUID().toString()+"aA1!";
        Context.getUserService().createUser(user,password);
        SyncAuditService audit=service(); org.openmrs.api.context.Credentials admin=getCredentials();
        Context.logout();
        try {
            Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(name,password));
            assertThrows(org.openmrs.api.APIAuthenticationException.class,() -> audit.begin(record()));
        } finally { Context.logout(); Context.authenticate(admin); }
        role.addPrivilege(privilege); Context.getUserService().saveRole(role);
        Context.logout();
        try {
            Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(name,password));
            String id=audit.begin(record()); audit.finish(id,"CONFIRMED","OK");
        } finally { Context.logout(); Context.authenticate(admin); }
    }
}

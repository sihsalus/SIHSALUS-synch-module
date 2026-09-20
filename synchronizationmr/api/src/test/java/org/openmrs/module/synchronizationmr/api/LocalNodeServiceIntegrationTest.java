package org.openmrs.module.synchronizationmr.api;

import java.util.concurrent.*;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.GlobalProperty;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Credentials;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class LocalNodeServiceIntegrationTest extends BaseModuleContextSensitiveTest {
	
	@BeforeEach
    public void prepareNode() throws Exception {
        new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(),
                new JdbcConnection(getConnection())).update("");
        try (java.sql.Statement s = getConnection().createStatement()) {
            s.executeUpdate("update synchronizationmr_local_node set server_id = null, patient_sequence = 0, encounter_sequence = 0 where singleton_id = 1");
        }
        configure("testServer_1");
    }
	
	private void configure(String value) {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", value));
	}
	
	private LocalNodeService service() {
		return Context.getService(LocalNodeService.class);
	}
	
	private String scalar(String query) throws Exception {
        try (java.sql.Statement s = getConnection().createStatement(); java.sql.ResultSet r = s.executeQuery(query)) {
            assertTrue(r.next()); return r.getString(1);
        }
    }
	
	@Test
    public void usesConfiguredIdWithoutGeneratingUuidOrAdvancingCounters() throws Exception {
        String patients = scalar("select count(*) from patient");
        assertEquals("testServer_1", service().getLocalServerId());
        assertEquals("testServer_1", service().getLocalServerId());
        assertEquals("testServer_1", scalar("select server_id from synchronizationmr_local_node"));
        assertEquals("0", scalar("select patient_sequence from synchronizationmr_local_node"));
        assertEquals("0", scalar("select encounter_sequence from synchronizationmr_local_node"));
        assertEquals(patients, scalar("select count(*) from patient"));
        assertThrows(java.sql.SQLException.class, () -> scalar("select node_uuid from synchronizationmr_local_node"));
    }
	
	@Test
    public void missingOrInvalidConfigurationDoesNotCreateIdentity() throws Exception {
        for (String value : new String[] { "", " ", "posta 1", "../posta", "a/b", new String(new char[101]).replace('\0', 'a') }) {
            configure(value);
            assertThrows(APIException.class, () -> service().getLocalServerId());
        }
        Context.getAdministrationService().purgeGlobalProperty(Context.getAdministrationService().getGlobalPropertyObject("server.id"));
        assertThrows(APIException.class, () -> service().getLocalServerId());
        assertNull(scalar("select server_id from synchronizationmr_local_node"));
    }
	
	@Test
    public void rejectsRenamingEstablishedOrigin() throws Exception {
        service().getLocalServerId();
        configure("testServer_2");
        assertThrows(APIException.class, () -> service().getLocalServerId());
        assertEquals("testServer_1", scalar("select server_id from synchronizationmr_local_node"));
    }
	
	@Test
	public void persistsWithoutOuterTransaction() throws Exception {
		TestTransaction.flagForCommit();
		TestTransaction.end();
		try {
			assertEquals("testServer_1", service().getLocalServerId());
		}
		finally {
			TestTransaction.start();
		}
		assertEquals("testServer_1", scalar("select server_id from synchronizationmr_local_node"));
	}
	
	@Test
	public void identityRollsBackWithCaller() throws Exception {
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		service().getLocalServerId();
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertNull(scalar("select server_id from synchronizationmr_local_node"));
	}
	
	@Test
    public void simultaneousFirstCallsUseSameConfiguredId() throws Exception {
        Credentials credentials = getCredentials();
        TestTransaction.flagForCommit(); TestTransaction.end();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            Callable<String> initialize = () -> {
                Context.openSession();
                try {
                    Context.authenticate(credentials); ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Tiempo de espera agotado"); }
                    return service().getLocalServerId();
                } finally { Context.closeSession(); }
            };
            Future<String> a = pool.submit(initialize), b = pool.submit(initialize);
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            assertEquals("testServer_1", a.get(20, TimeUnit.SECONDS));
            assertEquals("testServer_1", b.get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); TestTransaction.start(); }
        assertEquals("0", scalar("select patient_sequence from synchronizationmr_local_node"));
    }
}

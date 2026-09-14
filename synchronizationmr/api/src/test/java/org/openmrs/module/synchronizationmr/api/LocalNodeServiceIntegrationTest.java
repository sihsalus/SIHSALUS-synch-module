/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.api;

import java.util.UUID;
import java.util.concurrent.*;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Credentials;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

/** Comprueba la identidad local sin guardar pacientes ni depender de su interceptor. */
public class LocalNodeServiceIntegrationTest extends BaseModuleContextSensitiveTest {
	
	@BeforeEach
	public void prepareNode() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		// Solo en la base de pruebas: simula una instalación todavía sin identidad.
		executeSql("update synchronizationmr_local_node set node_uuid = null, patient_sequence = 0 where singleton_id = 1");
	}
	
	private void executeSql(String sql) throws Exception {
        try (java.sql.Statement statement = getConnection().createStatement()) {
            statement.executeUpdate(sql);
        }
    }
	
	private String scalar(String sql) throws Exception {
        try (java.sql.Statement statement = getConnection().createStatement();
                java.sql.ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
	
	private LocalNodeService service() {
		return Context.getService(LocalNodeService.class);
	}
	
	@Test
	public void createsStableUuidWithoutPatientsOrEvents() throws Exception {
		String patients = scalar("select count(*) from patient");
		String identities = scalar("select count(*) from synchronizationmr_patient_identity");
		String events = scalar("select count(*) from synchronizationmr_patient_event");
		String uuid = service().getOrCreateNodeUuid();
		assertEquals(uuid, UUID.fromString(uuid).toString());
		assertEquals(uuid, service().getOrCreateNodeUuid());
		assertEquals(uuid, scalar("select node_uuid from synchronizationmr_local_node where singleton_id = 1"));
		assertEquals("0", scalar("select patient_sequence from synchronizationmr_local_node where singleton_id = 1"));
		assertEquals(patients, scalar("select count(*) from patient"));
		assertEquals(identities, scalar("select count(*) from synchronizationmr_patient_identity"));
		assertEquals(events, scalar("select count(*) from synchronizationmr_patient_event"));
		System.out.println("NODO PREPARADO SIN CREAR PACIENTES: " + uuid);
	}
	
	@Test
	public void createsAndPersistsUuidWithoutOuterTransaction() throws Exception {
		TestTransaction.flagForCommit();
		TestTransaction.end();
		try {
			String uuid = service().getOrCreateNodeUuid();
			assertEquals(uuid, service().getOrCreateNodeUuid());
		}
		finally {
			TestTransaction.start();
		}
		assertNotNull(scalar("select node_uuid from synchronizationmr_local_node where singleton_id = 1"));
		assertEquals("0", scalar("select patient_sequence from synchronizationmr_local_node where singleton_id = 1"));
	}
	
	@Test
	public void initializationRollsBackWithCallerTransaction() throws Exception {
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		assertNotNull(service().getOrCreateNodeUuid());
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertNull(scalar("select node_uuid from synchronizationmr_local_node where singleton_id = 1"));
	}
	
	@Test
    public void simultaneousFirstCallsReturnSameUuid() throws Exception {
        Credentials credentials = getCredentials();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<String> initialize = () -> {
                Context.openSession();
                try {
                    Context.authenticate(credentials);
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("No se iniciaron las llamadas simultáneas");
                    }
                    return service().getOrCreateNodeUuid();
                }
                finally {
                    Context.closeSession();
                }
            };
            Future<String> a = pool.submit(initialize);
            Future<String> b = pool.submit(initialize);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        finally {
            start.countDown();
            pool.shutdownNow();
            TestTransaction.start();
        }
        assertEquals("0", scalar("select patient_sequence from synchronizationmr_local_node where singleton_id = 1"));
    }
}

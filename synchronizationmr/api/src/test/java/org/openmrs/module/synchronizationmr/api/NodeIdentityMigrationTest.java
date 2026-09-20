package org.openmrs.module.synchronizationmr.api;

import java.sql.*;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NodeIdentityMigrationTest {
	
	@Test
    public void installsFromScratchAndKeepsIdentityAndCountersOnRestart() throws Exception {
        try (Connection c = database(); Statement s = c.createStatement()) {
            Liquibase migration = migration(c);
            migration.update("");
            try (ResultSet r = s.executeQuery("select singleton_id, server_id, patient_sequence, encounter_sequence from synchronizationmr_local_node")) {
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
                assertNull(r.getString(2));
                assertEquals(0, r.getLong(3));
                assertEquals(0, r.getLong(4));
                assertFalse(r.next());
            }
            assertThrows(SQLException.class, () -> s.executeQuery("select node_uuid from synchronizationmr_local_node"));
            s.execute("insert into synchronizationmr_patient_identity (patient_id, patient_uuid, origin_server_id, entity_sequence) values (42, 'patient-uuid', 'testServer_1', 1)");
            s.execute("insert into synchronizationmr_patient_receipt (origin_server_id, confirmed_sequence) values ('testServer_2', 3)");
            s.execute("select origin_server_id, payload_json from synchronizationmr_encounter_event");
            s.execute("select payload_json from synchronizationmr_patient_event");
            s.execute("update synchronizationmr_local_node set server_id = 'testServer_1', patient_sequence = 1, encounter_sequence = 2");
            c.commit();
            migration.update("");
            try (ResultSet r = s.executeQuery("select server_id, patient_sequence, encounter_sequence from synchronizationmr_local_node")) {
                assertTrue(r.next());
                assertEquals("testServer_1", r.getString(1));
                assertEquals(1, r.getLong(2));
                assertEquals(2, r.getLong(3));
                assertFalse(r.next());
            }
            try (ResultSet r = s.executeQuery("select origin_server_id from synchronizationmr_patient_identity where patient_id = 42")) {
                assertTrue(r.next());
                assertEquals("testServer_1", r.getString(1));
            }
        }
    }
	
	private Connection database() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:identity-" + UUID.randomUUID(), "sa", "");
        try (Statement s = c.createStatement()) {
            s.execute("create table patient (patient_id int primary key)");
            s.execute("create table encounter (encounter_id int primary key)");
            s.execute("create table orders (order_id int primary key)");
            s.execute("insert into patient values (42)");
            s.execute("insert into encounter values (7)");
        }
        return c;
    }
	
	private Liquibase migration(Connection c) throws Exception {
		return new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(c));
	}
	
	@Test
    public void upgradesEmptySyncTablesAndPreservesClinicalRows() throws Exception {
        try (Connection c = database(); Statement s = c.createStatement()) {
            Liquibase migration = migration(c);
            migration.update(6, "");
            s.execute("update synchronizationmr_local_node set node_uuid = 'obsolete-node'");
            c.commit();
            migration.update(""); migration.update("");
            assertThrows(SQLException.class, () -> s.executeQuery("select node_uuid from synchronizationmr_local_node"));
            try (ResultSet r = s.executeQuery("select patient_id from patient")) { assertTrue(r.next()); assertEquals(42, r.getInt(1)); }
            try (ResultSet r = s.executeQuery("select encounter_id from encounter")) { assertTrue(r.next()); assertEquals(7, r.getInt(1)); }
            try (ResultSet r = s.executeQuery("select server_id from synchronizationmr_local_node")) { assertTrue(r.next()); assertNull(r.getString(1)); }
            s.execute("select origin_server_id from synchronizationmr_patient_identity");
            s.execute("select origin_server_id from synchronizationmr_patient_receipt");
            s.execute("select origin_server_id from synchronizationmr_encounter_event");
        }
    }
	
	@Test
    public void stopsRatherThanReinterpretingOldOrigins() throws Exception {
        try (Connection c = database(); Statement s = c.createStatement()) {
            Liquibase migration = migration(c);
            migration.update(6, "");
            s.execute("insert into synchronizationmr_patient_identity values (42, 'patient-uuid', 'old-origin-uuid', 1)");
            c.commit();
            assertThrows(Exception.class, () -> migration.update(""));
            try (ResultSet r = s.executeQuery("select origin_node_uuid from synchronizationmr_patient_identity")) {
                assertTrue(r.next()); assertEquals("old-origin-uuid", r.getString(1));
            }
        }
    }
}

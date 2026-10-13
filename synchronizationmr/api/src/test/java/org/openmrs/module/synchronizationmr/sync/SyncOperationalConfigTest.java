package org.openmrs.module.synchronizationmr.sync;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SyncOperationalConfigTest {

    @Test public void derivesAllRoutesAndNormalizesTrailingSlashWithoutLosingContext() {
        global("enabled", "true");
        global("masterBaseUrl", " https://master.example:8443/hospital/openmrs/ ");
        Map<String, String> settings = resolve();
        String prefix = "https://master.example:8443/hospital/openmrs/moduleServlet/synchronizationmr/";
        assertEquals(prefix + "patientSync", settings.get("SYNCMR_MASTER_ENDPOINT"));
        assertEquals(prefix + "encounterSync", settings.get("SYNCMR_MASTER_ENCOUNTER_ENDPOINT"));
        assertEquals(prefix + "orderSync", settings.get("SYNCMR_MASTER_ORDER_ENDPOINT"));
    }

    @Test public void supportsRootDeploymentAndIpv6() {
        global("enabled", "true");
        global("masterBaseUrl", "https://[::1]:8443/");
        assertEquals("https://[::1]:8443/moduleServlet/synchronizationmr/patientSync",
                resolve().get("SYNCMR_MASTER_ENDPOINT"));
    }

    @Test public void rejectsMissingInsecureOrAmbiguousBaseBeforeStartingTransport() {
        global("enabled", "true");
        for (String url : new String[] {"", "http://master/openmrs", "https:///openmrs",
                "https://user:secret@master/openmrs", "https://master/openmrs?token=secret",
                "https://master/openmrs#fragment", "https://master:0/openmrs", "https://master:65536/openmrs",
                "https://master/a/../openmrs", "https://master/a/%2e%2e/openmrs", "https://master/open mrs"}) {
            global("masterBaseUrl", url);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, this::resolve);
            assertFalse(failure.getMessage().contains("secret"));
        }
    }

    @Test public void oldEndpointPropertiesCannotOverrideBaseOrEnableIndividualFlows() {
        global("enabled", "true");
        global("masterBaseUrl", "https://correct/openmrs");
        global("masterPatientEndpoint", "https://wrong/patientSync");
        global("masterEncounterEndpoint", "");
        global("masterOrderEndpoint", "https://wrong/orderSync");
        assertEquals("https://correct/openmrs/moduleServlet/synchronizationmr/orderSync",
                resolve().get("SYNCMR_MASTER_ORDER_ENDPOINT"));
        assertEquals("https://correct/openmrs/moduleServlet/synchronizationmr/encounterSync",
                resolve().get("SYNCMR_MASTER_ENCOUNTER_ENDPOINT"));
        global("masterBaseUrl", "");
        assertThrows(IllegalArgumentException.class, this::resolve);
    }

    @Test public void disabledMasterDoesNotRequireOrUseRemoteAddress() {
        global("masterBaseUrl", "unused-invalid-address");
        assertFalse(new PatientSyncScheduleConfig(resolve()).enabled);
        assertFalse(resolve().containsKey("SYNCMR_MASTER_ENDPOINT"));
    }
    private final Map<String, String> globals = new HashMap<>();
    private final Properties runtime = new Properties();

    private void global(String key, String value) {
        globals.put("synchronizationmr." + key, value);
    }

    private Map<String, String> resolve() {
        return SyncOperationalConfig.resolve(globals::get, runtime);
    }

    @Test public void readsPropertiesAndReturnsStartupSnapshot() {
        global("intervalSeconds", "90");
        Map<String, String> settings = resolve();
        global("intervalSeconds", "120");
        assertEquals("90", settings.get("SYNCMR_INTERVAL_SECONDS"));
        assertEquals("120", resolve().get("SYNCMR_INTERVAL_SECONDS"));
    }

    @Test public void freshInstallationHasBothWorkersDisabledWithoutCredentials() {
        Map<String, String> settings = resolve();
        assertFalse(new PatientSyncScheduleConfig(settings).enabled);
        assertFalse(new PatientPreparationScheduler.Config(settings).enabled);
        assertFalse(settings.containsKey("SYNCMR_MASTER_ORDER_ENDPOINT"));
    }

    @Test public void canConfigureAllFlowsFromPropertiesWithoutScript() {
        global("enabled", "true");
        global("intervalSeconds", "90");
        global("eventsPerCycle", "7");
        global("originsPageSize", "3");
        global("masterServerId", "master");
        global("masterBaseUrl", "https://master/openmrs");
        global("localUsername", "local-tech");
        global("remoteUsername", "remote-tech");
        global("prepareExistingEnabled", "true");
        global("prepareEncountersEnabled", "true");
        global("prepareOrdersEnabled", "true");
        global("prepareResultsEnabled", "true");
        global("prepareBatchSize", "12");
        runtime.setProperty("synchronizationmr.localPassword", "local-test-secret");
        runtime.setProperty("synchronizationmr.remotePassword", "remote-test-secret");
        Map<String, String> settings = resolve();
        PatientSyncScheduleConfig sync = new PatientSyncScheduleConfig(settings);
        PatientPreparationScheduler.Config preparation = new PatientPreparationScheduler.Config(settings);
        assertEquals(90, sync.intervalSeconds);
        assertEquals(7, sync.limits.eventsPerCycle);
        assertEquals(3, sync.limits.originsPageSize);
        assertEquals("https://master/openmrs/moduleServlet/synchronizationmr/orderSync", sync.orderEndpoint);
        assertEquals("remote-test-secret", sync.remotePassword);
        assertTrue(preparation.results);
        assertEquals(12, preparation.batchSize);
        assertEquals("local-test-secret", preparation.password);
    }

    @Test public void neverUsesGlobalPasswordsAndPreservesSecretWhitespace() {
        global("localPassword", "must-not-be-used");
        assertFalse(resolve().containsKey("SYNCMR_LOCAL_PASSWORD"));
        runtime.setProperty("synchronizationmr.localPassword", " file-secret ");
        assertEquals(" file-secret ", resolve().get("SYNCMR_LOCAL_PASSWORD"));
    }

    @Test public void enabledWorkerFailsIfSecretsAreOnlyInGlobalProperties() {
        global("enabled", "true");
        global("masterServerId", "master");
        global("masterBaseUrl", "https://master/openmrs");
        global("localUsername", "local-tech");
        global("remoteUsername", "remote-tech");
        global("localPassword", "must-not-be-used");
        global("remotePassword", "must-not-be-used");
        assertThrows(IllegalArgumentException.class, () -> new PatientSyncScheduleConfig(resolve()));
    }

    @Test public void rejectsUnsafeLimitsAndKeepsDefaults() {
        SyncCycleLimits defaults = SyncCycleLimits.from(Collections.emptyMap());
        assertEquals(100, defaults.eventsPerCycle);
        assertEquals(100, defaults.originsPageSize);
        assertThrows(IllegalArgumentException.class, () -> new SyncCycleLimits(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new SyncCycleLimits(101, 100));
        assertThrows(IllegalArgumentException.class, () -> new SyncCycleLimits(100, 0));
        assertThrows(IllegalArgumentException.class, () -> new SyncCycleLimits(100, 101));
        assertThrows(IllegalArgumentException.class,
                () -> SyncCycleLimits.from(Collections.singletonMap("SYNCMR_EVENTS_PER_CYCLE", "invalid")));
    }

    @Test public void masterPreparationDoesNotRequireRemoteSecretsOrEnableTransport() {
        global("prepareExistingEnabled", "true");
        global("localUsername", "master-tech");
        runtime.setProperty("synchronizationmr.localPassword", "local-test-secret");
        assertTrue(new PatientPreparationScheduler.Config(resolve()).enabled);
        assertFalse(new PatientSyncScheduleConfig(resolve()).enabled);
    }
}

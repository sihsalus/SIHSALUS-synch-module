package org.openmrs.module.synchronizationmr.sync;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.net.URI;
import java.net.URISyntaxException;
import org.openmrs.api.context.Context;

/** Resolves a startup snapshot. Secrets never come from global properties. */
public final class SyncOperationalConfig {
	
	private SyncOperationalConfig() {
	}
	
	public static Map<String, String> load() {
        return resolve(key -> Context.getAdministrationService().getGlobalProperty(key),
                Context.getRuntimeProperties());
    }
	
	public static Map<String, String> resolve(Function<String, String> global, Properties runtime) {
        Map<String, String> values = new HashMap<>();
        String[][] settings = {
            {"ENABLED", "enabled", "false"},
            {"INTERVAL_SECONDS", "intervalSeconds", "60"},
            {"EVENTS_PER_CYCLE", "eventsPerCycle", "100"},
            {"ORIGINS_PAGE_SIZE", "originsPageSize", "100"},
            {"MASTER_SERVER_ID", "masterServerId", ""},
            {"LOCAL_USERNAME", "localUsername", ""},
            {"REMOTE_USERNAME", "remoteUsername", ""},
            {"PREPARE_EXISTING_ENABLED", "prepareExistingEnabled", "false"},
            {"PREPARE_ENCOUNTERS_ENABLED", "prepareEncountersEnabled", "false"},
            {"PREPARE_ORDERS_ENABLED", "prepareOrdersEnabled", "false"},
            {"PREPARE_RESULTS_ENABLED", "prepareResultsEnabled", "false"},
            {"PREPARE_BATCH_SIZE", "prepareBatchSize", "25"},
            {"PREPARE_INTERVAL_SECONDS", "prepareIntervalSeconds", "60"}
        };
        for (String[] setting : settings) {
            String configured = value(global, setting[1], setting[2]);
            if (!configured.isEmpty()) {
                values.put("SYNCMR_" + setting[0], configured);
            }
        }
        if ("true".equals(values.get("SYNCMR_ENABLED"))) {
            String base = masterBaseUrl(value(global, "masterBaseUrl", ""));
            String service = base + "/moduleServlet/synchronizationmr/";
            values.put("SYNCMR_MASTER_ENDPOINT", service + "patientSync");
            values.put("SYNCMR_MASTER_ENCOUNTER_ENDPOINT", service + "encounterSync");
            values.put("SYNCMR_MASTER_ORDER_ENDPOINT", service + "orderSync");
        }
        secret(values, runtime, "localPassword", "SYNCMR_LOCAL_PASSWORD");
        secret(values, runtime, "remotePassword", "SYNCMR_REMOTE_PASSWORD");
        return values;
    }
	
	private static String value(Function<String, String> global, String name, String fallback) {
		String configured = global.apply("synchronizationmr." + name);
		return configured == null ? fallback : configured.trim();
	}
	
	private static String masterBaseUrl(String configured) {
		try {
			URI uri = new URI(configured);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
			        || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0
			        || uri.getPort() > 65535) {
				throw new IllegalArgumentException("Invalid masterBaseUrl: HTTPS application URL required");
			}
			for (String segment : uri.getPath().split("/")) {
				if (".".equals(segment) || "..".equals(segment)) {
					throw new IllegalArgumentException("Invalid masterBaseUrl path");
				}
			}
			String base = uri.toASCIIString();
			while (base.endsWith("/")) {
				base = base.substring(0, base.length() - 1);
			}
			return base;
		}
		catch (URISyntaxException invalid) {
			// Do not include user-supplied URLs, which may contain credentials.
			throw new IllegalArgumentException("Invalid masterBaseUrl: HTTPS application URL required");
		}
	}
	
	private static void secret(Map<String, String> values, Properties runtime, String name, String variable) {
		String password = runtime == null ? null : runtime.getProperty("synchronizationmr." + name);
		if (password != null) {
			values.put(variable, password);
		}
	}
}

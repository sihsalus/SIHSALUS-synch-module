package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.sql.*;
import org.hibernate.Session;
import org.openmrs.Obs;
import org.openmrs.api.context.Context;
import static org.openmrs.module.synchronizationmr.sync.EventJson.*;

/** Validated historical links, completed atomically after all snapshot observations have IDs. */
public final class EncounterObservationVersions {
    private final Map<String, String> links = new LinkedHashMap<>();
    private final Set<String> included = new HashSet<>();
    private final String patientUuid;

    public EncounterObservationVersions(EncounterIncomingEvent event) {
        patientUuid = event.patientUuid;
        for (JsonNode item : event.root.get("payload").path("obs")) collect(item);
        for (String start : links.keySet()) {
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null) {
                if (!path.add(current) || path.size() > 1000) throw invalid();
                if (included.contains(current)) current = links.get(current);
                else {
                    Obs previous = Context.getObsService().getObsByUuid(current);
                    if (previous == null) throw dependency("previousVersionUuid");
                    if (previous.getPerson() == null || !patientUuid.equals(previous.getPerson().getUuid())) throw conflict();
                    current = previous.getPreviousVersion() == null ? null : previous.getPreviousVersion().getUuid();
                }
            }
        }
    }

    // Called only after toEncounter has validated shape, depth, duplicate UUIDs and patient ownership.
    private void collect(JsonNode item) {
        String id = reference(text(item, "uuid", true));
        included.add(id);
        String previous = text(item, "previousVersionUuid", false);
        if (previous != null) links.put(id, reference(previous));
        for (JsonNode child : item.path("groupMembers")) collect(child);
    }

    public void apply(Session session, Connection connection) throws SQLException {
        for (Map.Entry<String, String> link : links.entrySet()) {
            Obs observation = Context.getObsService().getObsByUuid(link.getKey());
            Obs previous = Context.getObsService().getObsByUuid(link.getValue());
            if (observation == null || previous == null || !patientUuid.equals(observation.getPerson().getUuid())
                || !patientUuid.equals(previous.getPerson().getUuid())) throw conflict();
            // An import foreign key, not a clinical edit: saveObs would create a new UUID/version.
            try (PreparedStatement statement = connection.prepareStatement(
                "update obs set previous_version = ? where obs_id = ? and previous_version is null")) {
                statement.setInt(1, previous.getObsId()); statement.setInt(2, observation.getObsId());
                if (statement.executeUpdate() != 1) throw conflict();
            }
            session.refresh(observation);
        }
    }
}

package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openmrs.*;
import org.openmrs.api.APIException;
import static org.junit.jupiter.api.Assertions.*;

public class EncounterUpdateEventTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private String event() {
		Encounter e = new Encounter();
		e.setPatient(new Patient());
		e.setEncounterType(new EncounterType());
		e.setEncounterDatetime(new Date(1234));
		return EncounterUpdateEvent.create(EncounterUpdateEvent.capture(e), Collections.singleton("locationUuid"),
		    "posta_a", 2, Instant.EPOCH);
	}

	@Test public void acceptsExplicitContractAndNormalizesNativeDatePrecision() throws Exception {
        String json = event();
        EncounterIncomingEvent envelope = new EncounterIncomingEvent(json);
        assertTrue(envelope.update); assertFalse(envelope.addition);
        assertEquals(2, envelope.sequence);
        assertEquals("1970-01-01T00:00:01Z", mapper.readTree(json).path("payload").path("encounterDatetime").asText());
        assertThrows(APIException.class, envelope::toEncounter);
    }

	@Test public void rejectsUnknownDuplicateAndEmptyGroups() throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(event());
        for (String unknown : Arrays.asList("obs", "visit", "voided", "orders")) {
            json.putArray("changedGroups").add(unknown);
            assertThrows(APIException.class, () -> new EncounterIncomingEvent(json.toString()));
        }
        json.putArray("changedGroups").add("formUuid").add("formUuid");
        assertThrows(APIException.class, () -> new EncounterIncomingEvent(json.toString()));
        json.putArray("changedGroups");
        assertThrows(APIException.class, () -> new EncounterIncomingEvent(json.toString()));
    }

	@Test public void rejectsBadSequenceSchemaAndTimestamp() throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(event());
        for (double seq : new double[] { 0, -1, 2.5 }) {
            json.put("entitySequence", seq);
            assertThrows(APIException.class, () -> new EncounterUpdateEvent(json.toString()));
        }
        json.put("entitySequence", 2); json.put("schemaVersion", 4);
        assertThrows(APIException.class, () -> new EncounterUpdateEvent(json.toString()));
        json.put("schemaVersion", 5); json.put("occurredAt", "2026-01-01T00:00:00.000001Z");
        assertThrows(APIException.class, () -> new EncounterUpdateEvent(json.toString()));
    }

	@Test public void rejectsTrailingJsonDuplicateKeysAndUnexpectedPayload() throws Exception {
        assertThrows(APIException.class, () -> new EncounterIncomingEvent("null"));
        assertThrows(APIException.class, () -> new EncounterUpdateEvent(event() + " {}"));
        assertThrows(APIException.class, () -> new EncounterUpdateEvent(event().replace("\"UPDATE\"", "\"UPDATE\",\"operation\":\"UPDATE\"")));
        ObjectNode json = (ObjectNode) mapper.readTree(event());
        ((ObjectNode) json.get("payload")).putArray("obs");
        assertThrows(APIException.class, () -> new EncounterIncomingEvent(json.toString()));
    }

	@Test public void rejectsProviderUuidOrRoleDuplicates() throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(event());
        com.fasterxml.jackson.databind.node.ArrayNode providers = ((ObjectNode) json.get("payload")).putArray("encounterProviders");
        ObjectNode first = providers.addObject();
        first.put("uuid", UUID.randomUUID().toString());
        first.put("providerUuid", "profesional"); first.put("encounterRoleUuid", "rol");
        ObjectNode second = first.deepCopy(); second.put("uuid", UUID.randomUUID().toString()); providers.add(second);
        assertThrows(APIException.class, () -> new EncounterUpdateEvent(json.toString()));
        second.put("providerUuid", "otro"); second.put("uuid", first.path("uuid").asText());
        assertThrows(APIException.class, () -> new EncounterUpdateEvent(json.toString()));
    }
}

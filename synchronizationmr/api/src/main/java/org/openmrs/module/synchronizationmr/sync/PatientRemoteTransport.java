package org.openmrs.module.synchronizationmr.sync;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;

/** Comunicación con el maestro; separada para probar cortes sin usar una red real. */
public interface PatientRemoteTransport {
	
	JsonNode get(String query) throws IOException;
	
	JsonNode receive(String json) throws IOException;
}

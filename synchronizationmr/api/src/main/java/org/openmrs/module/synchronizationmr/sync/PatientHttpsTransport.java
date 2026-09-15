package org.openmrs.module.synchronizationmr.sync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.net.ssl.HttpsURLConnection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Transporte con TLS estándar, tiempo de espera y sin redirecciones de credenciales. */
public class PatientHttpsTransport implements PatientRemoteTransport {
	
	private final URL endpoint;
	
	private final String authorization;
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	public PatientHttpsTransport(String endpoint, String username, String password) throws IOException {
		this.endpoint = new URL(endpoint);
		if (!"https".equals(this.endpoint.getProtocol()) || this.endpoint.getHost().isEmpty()
		        || this.endpoint.getUserInfo() != null || this.endpoint.getQuery() != null || this.endpoint.getRef() != null
		        || username == null || username.isEmpty() || username.contains(":") || password == null
		        || password.isEmpty()) {
			throw new IllegalArgumentException("Se requiere una ruta HTTPS sin credenciales y una cuenta técnica válida");
		}
		authorization = "Basic "
		        + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
	}
	
	@Override
	public JsonNode get(String query) throws IOException {
		return request(query, null);
	}
	
	@Override
	public JsonNode receive(String json) throws IOException {
		return request("resource=receive", json);
	}
	
	private JsonNode request(String query, String json) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(endpoint.toExternalForm() + "?" + query).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Authorization", authorization);
            connection.setRequestProperty("Accept", "application/json");
            if (json != null) {
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                if (body.length > 1048576) { throw new IOException("El evento supera el límite de envío"); }
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status != 200) { throw new IOException("El maestro rechazó la petición HTTP: " + status); }
            // El ciclo solicita páginas de un evento para acotar memoria y facilitar el reintento.
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 2097152) { throw new IOException("Respuesta del maestro demasiado grande"); }
                    bytes.write(buffer, 0, count);
                }
                JsonNode result;
                try { result = mapper.readTree(bytes.toByteArray()); }
                catch (IOException invalid) { throw new IOException("Respuesta JSON del maestro inválida"); }
                if (result == null || !result.isObject()) { throw new IOException("Respuesta del maestro inválida"); }
                return result;
            }
        } finally { connection.disconnect(); }
    }
}

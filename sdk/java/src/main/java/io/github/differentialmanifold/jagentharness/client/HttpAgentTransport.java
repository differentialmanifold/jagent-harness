package io.github.differentialmanifold.jagentharness.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** JDK HTTP transport: no Spring, OkHttp, JDBC, YAML or server runtime dependency. */
public final class HttpAgentTransport implements AgentTransport {
    private final URL endpoint;
    private final String token;
    private final ObjectMapper json =
            new ObjectMapper()
                    .configure(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES,
                            false);

    public HttpAgentTransport(String baseUrl, String token) throws MalformedURLException {
        endpoint = new URL(baseUrl.replaceAll("/$", "") + "/api/v1/chat");
        this.token = token;
    }

    @Override
    public ChatResponse chat(ChatRequest request, Consumer<StreamEvent> events) throws IOException {
        HttpURLConnection c = (HttpURLConnection) endpoint.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10000);
        c.setReadTimeout(180000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", request.stream ? "text/event-stream" : "application/json");
        if (token != null && !token.isEmpty())
            c.setRequestProperty("Authorization", "Bearer " + token);
        byte[] body = json.writeValueAsBytes(request);
        c.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int status = c.getResponseCode();
            if (status >= 400) {
                String error = read(c.getErrorStream());
                throw new AgentHttpException(status, error);
            }
            if (!request.stream) {
                try (InputStream in = c.getInputStream()) {
                    return json.readValue(in, ChatResponse.class);
                }
            }
            ChatResponse response = null;
            try (BufferedReader in =
                    new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line, type = "message";
                StringBuilder data = new StringBuilder();
                while ((line = in.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            if ("response".equals(type))
                                response = json.readValue(data.toString(), ChatResponse.class);
                            else if ("error".equals(type))
                                throw new AgentHttpException(
                                        json.readTree(data.toString()).path("status").asInt(500),
                                        data.toString());
                            else if (events != null)
                                events.accept(json.readValue(data.toString(), StreamEvent.class));
                        }
                        type = "message";
                        data.setLength(0);
                    } else if (line.startsWith("event:")) type = line.substring(6).trim();
                    else if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).replaceFirst("^ ", ""));
                    }
                }
            }
            if (response == null)
                throw new IOException(
                        "Stream ended before the final response; delivery is uncertain. Inspect session history before sending another request");
            return response;
        } finally {
            c.disconnect();
        }
    }

    public String createSession(String title, String workspacePath) throws IOException {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("title", title);
        body.put("workspacePath", workspacePath);
        return management("POST", "/api/v1/sessions", body).path("sessionId").asText();
    }

    public ChatResponse state(String sessionId) throws IOException {
        return json.treeToValue(
                management("GET", "/api/v1/sessions/" + segment(sessionId) + "/state", null),
                ChatResponse.class);
    }

    public void stop(String runId) throws IOException {
        management(
                "POST",
                "/api/v1/runs/" + segment(runId) + "/stop",
                java.util.Collections.emptyMap());
    }

    private static String segment(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid ID");
        return id;
    }

    private com.fasterxml.jackson.databind.JsonNode management(
            String method, String path, Object body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint, path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty())
            c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                byte[] bytes = json.writeValueAsBytes(body);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            if (status >= 400) throw new AgentHttpException(status, read(c.getErrorStream()));
            try (InputStream in = c.getInputStream()) {
                return json.readTree(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "Empty error response";
        try (InputStream source = in;
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = source.read(b)) != -1) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

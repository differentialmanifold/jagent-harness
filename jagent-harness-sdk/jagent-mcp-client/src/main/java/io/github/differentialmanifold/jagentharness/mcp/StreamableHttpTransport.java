package io.github.differentialmanifold.jagentharness.mcp;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.StopRegistration;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class StreamableHttpTransport implements AutoCloseable {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final McpServerConfig config;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private volatile String sessionId;
    private volatile String protocolVersion;

    StreamableHttpTransport(McpServerConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Math.max(1, config.getConnectTimeoutSeconds()), TimeUnit.SECONDS)
                .readTimeout(Math.max(1, config.getRequestTimeoutSeconds()), TimeUnit.SECONDS)
                .writeTimeout(Math.max(1, config.getRequestTimeoutSeconds()), TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    JsonNode request(JsonNode message, JsonNode requestId, StopSignal stopSignal) throws IOException {
        Call call = httpClient.newCall(postRequest(message));
        StopSignal signal = stopSignal == null ? StopSignal.none() : stopSignal;
        try (StopRegistration ignored = signal.onStop(call::cancel);
             Response response = call.execute()) {
            signal.throwIfAborted();
            captureSession(response);
            if (response.code() == 404 && sessionId != null) {
                sessionId = null;
                throw new McpSessionExpiredException();
            }
            if (!response.isSuccessful()) {
                throw new McpProtocolException("MCP server returned HTTP " + response.code()
                        + responseMessage(response.body()));
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            String contentType = response.header("Content-Type", "");
            if (contentType.toLowerCase().startsWith("text/event-stream")) {
                return readSseResponse(body, requestId);
            }
            if (response.code() == 202 || body.contentLength() == 0L) {
                return null;
            }
            return objectMapper.readTree(body.byteStream());
        }
    }

    void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    String getSessionId() {
        return sessionId;
    }

    void resetSession() {
        sessionId = null;
        protocolVersion = null;
    }

    private Request postRequest(JsonNode message) throws IOException {
        RequestBody body = RequestBody.create(objectMapper.writeValueAsString(message), JSON);
        Request.Builder builder = requestBuilder().post(body);
        return builder.build();
    }

    private Request.Builder requestBuilder() {
        Request.Builder builder = new Request.Builder()
                .url(config.getUrl())
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
        for (Map.Entry<String, String> header : config.getHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            builder.header("MCP-Session-Id", sessionId);
        }
        if (protocolVersion != null && !protocolVersion.isEmpty()) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        return builder;
    }

    private JsonNode readSseResponse(ResponseBody body, JsonNode requestId) throws IOException {
        List<SseEvent> events = new SseEventReader().read(body.byteStream());
        for (SseEvent event : events) {
            if (event.getData() == null || event.getData().trim().isEmpty()) {
                continue;
            }
            JsonNode message = objectMapper.readTree(event.getData());
            if (requestId == null || sameRequestId(requestId, message.get("id"))) {
                return message;
            }
        }
        throw new McpProtocolException("MCP SSE response ended without a matching JSON-RPC response");
    }

    private boolean sameRequestId(JsonNode expected, JsonNode actual) {
        if (actual == null) {
            return false;
        }
        if (expected.isNumber() && actual.isNumber()) {
            return expected.asLong() == actual.asLong();
        }
        if (expected.isTextual() && actual.isTextual()) {
            return expected.asText().equals(actual.asText());
        }
        return expected.equals(actual);
    }

    private void captureSession(Response response) {
        String value = response.header("MCP-Session-Id");
        if (value != null && !value.trim().isEmpty()) {
            sessionId = value.trim();
        }
    }

    private String responseMessage(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        String content = body.string();
        return content.isEmpty() ? "" : ": " + content;
    }

    @Override
    public void close() {
        String activeSession = sessionId;
        if (activeSession != null) {
            Request request = requestBuilder().delete().build();
            try (Response ignored = httpClient.newCall(request).execute()) {
                // Session deletion is best effort.
            } catch (IOException ignored) {
                // Session deletion is best effort.
            }
        }
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}

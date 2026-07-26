package io.github.differentialmanifold.jagentharness.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.agent.StopRegistration;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;

public class McpClient implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "2025-11-25";

    private final McpServerConfig config;
    private final ObjectMapper objectMapper;
    private final StreamableHttpTransport transport;
    private final AtomicLong requestIds = new AtomicLong();
    private final ExecutorService cancellationExecutor;
    private volatile boolean initialized;
    private volatile String negotiatedProtocolVersion;

    public McpClient(McpServerConfig config, ObjectMapper objectMapper) {
        this.config = config.copy();
        this.objectMapper = objectMapper;
        this.transport = new StreamableHttpTransport(this.config, objectMapper);
        this.cancellationExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "jagent-mcp-cancel-" + McpClient.this.config.getName());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public synchronized void initialize() throws IOException {
        if (initialized) {
            return;
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "JAgentHarness");
        clientInfo.put("version", "0.7.1");

        JsonNode response = requestOnce("initialize", params, StopSignal.none());
        JsonNode result = requireResult(response, "initialize");
        String version = result.path("protocolVersion").asText(PROTOCOL_VERSION);
        negotiatedProtocolVersion = version;
        transport.setProtocolVersion(version);

        ObjectNode initializedNotification = message(null, "notifications/initialized", null);
        transport.request(initializedNotification, null, StopSignal.none());
        initialized = true;
    }

    public List<McpToolDescriptor> listTools() throws IOException {
        initialize();
        List<McpToolDescriptor> tools = new ArrayList<McpToolDescriptor>();
        String cursor = null;
        do {
            ObjectNode params = objectMapper.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = requireResult(requestWithSessionRecovery("tools/list", params, StopSignal.none()), "tools/list");
            JsonNode listed = result.path("tools");
            if (listed.isArray()) {
                for (JsonNode tool : listed) {
                    String name = tool.path("name").asText("").trim();
                    if (!name.isEmpty()) {
                        tools.add(new McpToolDescriptor(
                                name,
                                tool.path("description").asText(""),
                                tool.get("inputSchema")));
                    }
                }
            }
            cursor = result.path("nextCursor").asText("").trim();
            if (cursor.isEmpty()) {
                cursor = null;
            }
        } while (cursor != null);
        return Collections.unmodifiableList(tools);
    }

    public JsonNode callTool(String name, JsonNode arguments, StopSignal stopSignal) throws IOException {
        initialize();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? objectMapper.createObjectNode() : arguments);
        return requireResult(requestWithSessionRecovery("tools/call", params, stopSignal), "tools/call");
    }

    public String getNegotiatedProtocolVersion() {
        return negotiatedProtocolVersion;
    }

    public String getSessionId() {
        return transport.getSessionId();
    }

    private JsonNode requestWithSessionRecovery(String method,
                                                JsonNode params,
                                                StopSignal stopSignal) throws IOException {
        try {
            return requestOnce(method, params, stopSignal);
        } catch (McpSessionExpiredException e) {
            synchronized (this) {
                initialized = false;
                negotiatedProtocolVersion = null;
                transport.resetSession();
                initialize();
            }
            return requestOnce(method, params, stopSignal);
        }
    }

    private JsonNode requestOnce(String method, JsonNode params, StopSignal stopSignal) throws IOException {
        final long requestId = requestIds.incrementAndGet();
        final StopSignal signal = stopSignal == null ? StopSignal.none() : stopSignal;
        ObjectNode request = message(Long.valueOf(requestId), method, params);
        try (StopRegistration ignored = signal.onStop(() -> sendCancelled(requestId))) {
            try {
                return transport.request(request, request.path("id"), signal);
            } catch (IOException e) {
                signal.throwIfAborted();
                throw e;
            }
        }
    }

    private void sendCancelled(final long requestId) {
        cancellationExecutor.execute(new Runnable() {
            @Override
            public void run() {
                ObjectNode params = objectMapper.createObjectNode();
                params.put("requestId", requestId);
                params.put("reason", "Agent run was stopped");
                try {
                    transport.request(message(null, "notifications/cancelled", params), null, StopSignal.none());
                } catch (Exception ignored) {
                    // Cancellation notification is best effort; cancelling the active HTTP call is authoritative.
                }
            }
        });
    }

    private ObjectNode message(Long id, String method, JsonNode params) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        if (id != null) {
            message.put("id", id.longValue());
        }
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        return message;
    }

    private JsonNode requireResult(JsonNode response, String method) throws McpProtocolException {
        if (response == null) {
            throw new McpProtocolException("MCP " + method + " returned no JSON-RPC response");
        }
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            throw new McpProtocolException("MCP " + method + " failed: "
                    + error.path("message").asText(error.toString()));
        }
        JsonNode result = response.get("result");
        if (result == null) {
            throw new McpProtocolException("MCP " + method + " response has no result");
        }
        return result;
    }

    @Override
    public void close() {
        cancellationExecutor.shutdownNow();
        transport.close();
    }
}

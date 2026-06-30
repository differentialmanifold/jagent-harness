package io.github.differentialmanifold.jagentharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpClientIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> sessionHeaders = Collections.synchronizedList(new ArrayList<String>());
    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", new McpHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void initializesListsPaginatedToolsAndCallsToolFromJsonAndSseResponses() throws Exception {
        McpServerConfig config = new McpServerConfig();
        config.setName("demo");
        config.setUrl(endpoint);
        McpClient client = new McpClient(config, objectMapper);
        try {
            List<McpToolDescriptor> tools = client.listTools();
            assertEquals(2, tools.size());
            assertEquals("2025-11-25", client.getNegotiatedProtocolVersion());
            assertEquals("session-1", client.getSessionId());

            McpRemoteTool remoteTool = new McpRemoteTool("demo", tools.get(0), client, objectMapper);
            ToolExecutionResult result = remoteTool.execute(
                    new ToolContext("session", "turn"),
                    objectMapper.readTree("{\"value\":\"hello\"}"));

            assertEquals("echo: hello", result.getContent());
            assertEquals("demo__echo", remoteTool.getName());
            assertTrue(sessionHeaders.contains("session-1"));
        } finally {
            client.close();
        }
    }

    private class McpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            sessionHeaders.add(exchange.getRequestHeaders().getFirst("MCP-Session-Id"));
            JsonNode request = objectMapper.readTree(readAll(exchange.getRequestBody()));
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)
                    || "notifications/cancelled".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            JsonNode response;
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().set("MCP-Session-Id", "session-1");
                response = objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":"
                        + request.path("id").asLong()
                        + ",\"result\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                        + "\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
                json(exchange, response);
                return;
            }
            if ("tools/list".equals(method)) {
                boolean secondPage = request.path("params").has("cursor");
                String result = secondPage
                        ? "{\"tools\":[{\"name\":\"other\",\"description\":\"Other\",\"inputSchema\":{\"type\":\"object\"}}]}"
                        : "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"}}],\"nextCursor\":\"next\"}";
                response = objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":"
                        + request.path("id").asLong() + ",\"result\":" + result + "}");
                if (secondPage) {
                    sse(exchange, response);
                } else {
                    json(exchange, response);
                }
                return;
            }
            String value = request.path("params").path("arguments").path("value").asText();
            response = objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":"
                    + request.path("id").asLong()
                    + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"echo: "
                    + value + "\"}],\"isError\":false}}");
            sse(exchange, response);
        }

        private void json(HttpExchange exchange, JsonNode response) throws IOException {
            byte[] body = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        private void sse(HttpExchange exchange, JsonNode response) throws IOException {
            String json = objectMapper.writeValueAsString(response);
            int split = json.indexOf(",\"result\"") + 1;
            byte[] body = ("id: event-1\ndata: " + json.substring(0, split)
                    + "\ndata: " + json.substring(split) + "\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private String readAll(InputStream input) throws IOException {
        byte[] buffer = new byte[1024];
        StringBuilder result = new StringBuilder();
        int read;
        while ((read = input.read(buffer)) >= 0) {
            result.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return result.toString();
    }
}

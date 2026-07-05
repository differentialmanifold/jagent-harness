package io.github.differentialmanifold.jagentharness.mcp.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpRuntimeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    java.nio.file.Path tempDir;

    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", new Handler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void exposesToolDetailsAndCallsToolForConsoleDebugging() throws Exception {
        McpConfigurationManager manager = new McpConfigurationManager(
                tempDir, "mcp.json", null, objectMapper);
        McpRuntime runtime = new McpRuntime(manager, objectMapper);
        McpServerConfig config = new McpServerConfig();
        config.setUrl(endpoint);

        McpTestResult tested = runtime.test("demo", config);
        assertTrue(tested.isSuccess());
        assertEquals("Echo the provided value", tested.getToolDetails().get(0).getDescription());
        assertEquals("string", tested.getToolDetails().get(0)
                .getInputSchema().path("properties").path("value").path("type").asText());

        McpToolCallResult called = runtime.call(
                "demo", config, "echo", objectMapper.readTree("{\"value\":\"hello\"}"));
        assertTrue(called.isSuccess());
        assertEquals("echo: hello", called.getResult().path("content").get(0).path("text").asText());
    }

    @Test
    void refreshesStatusesAfterConfigurationChanges() {
        MemoryStore store = new MemoryStore();
        McpConfigurationManager manager = new McpConfigurationManager(
                tempDir, "mcp.json", store, objectMapper);
        McpRuntime runtime = new McpRuntime(manager, objectMapper);

        manager.saveDatabase(config(true, null));
        McpServerRuntimeStatus enabled = runtime.statuses(null).get("demo");
        assertEquals("available", enabled.getStatus());
        assertEquals(Collections.singletonList("echo"), enabled.getTools());

        manager.saveDatabase(config(false, Collections.singletonList("echo")));
        McpServerRuntimeStatus disabled = runtime.statuses(null).get("demo");
        assertEquals("disabled", disabled.getStatus());
        assertTrue(disabled.getTools().isEmpty());

        manager.saveDatabase(config(true, Collections.<String>emptyList()));
        McpServerRuntimeStatus filtered = runtime.statuses(null).get("demo");
        assertEquals("available", filtered.getStatus());
        assertTrue(filtered.getTools().isEmpty());
        assertEquals(Collections.singletonList("echo"), filtered.getAvailableTools());
    }

    private String config(boolean enabled, List<String> enabledTools) {
        com.fasterxml.jackson.databind.node.ObjectNode config = objectMapper.createObjectNode();
        config.put("transport", "streamable-http");
        config.put("url", endpoint);
        config.put("enabled", enabled);
        if (enabledTools != null) {
            config.set("enabledTools", objectMapper.valueToTree(enabledTools));
        }
        com.fasterxml.jackson.databind.node.ObjectNode servers = objectMapper.createObjectNode();
        servers.set("demo", config);
        com.fasterxml.jackson.databind.node.ObjectNode document = objectMapper.createObjectNode();
        document.set("mcpServers", servers);
        return document.toString();
    }

    private class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            JsonNode request = objectMapper.readTree(readAll(exchange.getRequestBody()));
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            if ("initialize".equals(method)) {
                respond(exchange, request, "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                        + "\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}");
                return;
            }
            if ("tools/list".equals(method)) {
                respond(exchange, request, "{\"tools\":[{\"name\":\"echo\","
                        + "\"description\":\"Echo the provided value\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                        + "\"value\":{\"type\":\"string\"}}}}]}");
                return;
            }
            String value = request.path("params").path("arguments").path("value").asText();
            respond(exchange, request, "{\"content\":[{\"type\":\"text\",\"text\":\"echo: "
                    + value + "\"}],\"isError\":false}");
        }

        private void respond(HttpExchange exchange, JsonNode request, String result) throws IOException {
            byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":"
                    + request.path("id").asLong() + ",\"result\":" + result + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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

    private static class MemoryStore implements KnowledgeFileStore {
        private final Map<String, KnowledgeFile> files = new LinkedHashMap<String, KnowledgeFile>();

        @Override
        public KnowledgeFile readFile(String path) {
            return readFile(KnowledgeScope.global(), path);
        }

        @Override
        public KnowledgeFile readFile(KnowledgeScope scope, String path) {
            return files.get(scope.getType() + ":" + scope.getId() + ":" + path);
        }

        @Override
        public List<KnowledgeFile> listFiles(String prefix) {
            KnowledgeFile file = readFile(prefix);
            return file == null ? Collections.<KnowledgeFile>emptyList() : Collections.singletonList(file);
        }

        @Override
        public KnowledgeFile writeFile(String path, String content, String contentType) {
            return writeFile(KnowledgeScope.global(), path, content, contentType);
        }

        @Override
        public KnowledgeFile writeFile(KnowledgeScope scope, String path, String content, String contentType) {
            KnowledgeFile file = new KnowledgeFile(
                    path, KnowledgeFile.TYPE_FILE, content, contentType, Instant.now(), Instant.now());
            files.put(scope.getType() + ":" + scope.getId() + ":" + path, file);
            return file;
        }

        @Override
        public void deleteFile(String path) {
            deleteFile(KnowledgeScope.global(), path);
        }

        @Override
        public void deleteFile(KnowledgeScope scope, String path) {
            files.remove(scope.getType() + ":" + scope.getId() + ":" + path);
        }
    }
}

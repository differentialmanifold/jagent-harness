package io.github.differentialmanifold.jagentharness.mcp.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.core.skill.*;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.builtin.SkillTool;
import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpRuntimeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir java.nio.file.Path tempDir;

    private HttpServer server;
    private String endpoint;
    private boolean extraTool;

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
    void agentAndProjectDefaultsUseIndependentConfigurationsAndCaches() {
        MemoryStore store = new MemoryStore();
        McpConfigurationManager manager =
                new McpConfigurationManager(tempDir, "mcp.json", store, objectMapper);
        McpRuntime runtime = new McpRuntime(manager, objectMapper);
        io.github.differentialmanifold.jagentharness.core.agent.AgentContext agent =
                new io.github.differentialmanifold.jagentharness.core.agent.AgentContext(
                        "chat",
                        "run",
                        "turn",
                        null,
                        null,
                        null,
                        Collections.singletonMap("personalAgentId", "agent"));
        try {
            manager.saveDatabase(config(true, null));
            assertEquals(1, runtime.listTools(null).size());
            assertTrue(runtime.listTools(agent).isEmpty());
            manager.saveDatabase(KnowledgeScope.agentGlobal(), config(false, null));
            assertEquals("disabled", runtime.agentStatuses().get("demo").getStatus());
            assertEquals("available", runtime.statuses(null).get("demo").getStatus());
            manager.saveDatabase(KnowledgeScope.agentGlobal(), config(true, null));
            assertEquals(1, runtime.listTools(agent).size());
            manager.deleteDatabase();
            assertTrue(runtime.listTools(null).isEmpty());
            assertEquals(1, runtime.listTools(agent).size());
        } finally {
            runtime.close();
        }
    }

    @Test
    void exposesToolDetailsAndCallsToolForConsoleDebugging() throws Exception {
        McpConfigurationManager manager =
                new McpConfigurationManager(tempDir, "mcp.json", null, objectMapper);
        McpRuntime runtime = new McpRuntime(manager, objectMapper);
        McpServerConfig config = new McpServerConfig();
        config.setUrl(endpoint);

        McpTestResult tested = runtime.test("demo", config);
        assertTrue(tested.isSuccess());
        assertEquals("Echo the provided value", tested.getToolDetails().get(0).getDescription());
        assertEquals(
                "string",
                tested.getToolDetails()
                        .get(0)
                        .getInputSchema()
                        .path("properties")
                        .path("value")
                        .path("type")
                        .asText());

        McpToolCallResult called =
                runtime.call(
                        "demo", config, "echo", objectMapper.readTree("{\"value\":\"hello\"}"));
        assertTrue(called.isSuccess());
        assertEquals(
                "echo: hello", called.getResult().path("content").get(0).path("text").asText());
    }

    @Test
    void refreshesStatusesAfterConfigurationChanges() {
        MemoryStore store = new MemoryStore();
        McpConfigurationManager manager =
                new McpConfigurationManager(tempDir, "mcp.json", store, objectMapper);
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

    @Test
    void skillLoadsExactToolsAndRetainsThemAcrossRunsAndRuntimeRestart() throws Exception {
        extraTool = true;
        MemoryStore files = new MemoryStore();
        InMemorySkillActivationStore activations = new InMemorySkillActivationStore();
        McpConfigurationManager manager =
                new McpConfigurationManager(tempDir, "mcp.json", files, objectMapper);
        String deferred =
                config(true, null)
                        .replace("\"enabled\":true", "\"enabled\":true,\"exposure\":\"on_skill\"");
        // Two independently named services provide the same remote echo function.
        JsonNode document = objectMapper.readTree(deferred);
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("mcpServers"))
                .set("other", document.path("mcpServers").path("demo").deepCopy());
        manager.saveDatabase(document.toString());
        ToolContext context = new ToolContext("session", "run-1", "turn-1");
        files.writeFile("skills/refund/SKILL.md", skill("demo", "echo"), "text/markdown");
        files.writeFile("skills/refund/details.md", skill("other", "echo"), "text/markdown");
        try (McpRuntime runtime = new McpRuntime(manager, objectMapper, activations)) {
            SkillTool skill =
                    new SkillTool(
                            objectMapper,
                            files,
                            new SkillActivationService(activations, () -> runtime));
            assertTrue(runtime.listTools(context).isEmpty());
            assertEquals(2, runtime.statuses(null).size()); // Discovery stays local.
            skill.execute(
                    context,
                    objectMapper.createObjectNode().put("path", "skills/refund/details.md"));
            assertTrue(runtime.listTools(context).isEmpty());
            JsonNode result =
                    objectMapper.readTree(
                            skill.execute(
                                            context,
                                            objectMapper
                                                    .createObjectNode()
                                                    .put("path", "skills/refund/SKILL.md"))
                                    .getContent());
            assertEquals(
                    "demo__echo", result.path("activatedTools").get(0).path("modelName").asText());
            assertEquals(Collections.singletonList("demo__echo"), names(runtime, context));
            skill.execute(
                    context, objectMapper.createObjectNode().put("path", "skills/refund/SKILL.md"));
            assertEquals(1, activations.find("session", "global").size());
            assertEquals(
                    1, runtime.listTools(new ToolContext("session", "run-2", "turn-2")).size());
            assertTrue(
                    runtime.listTools(new ToolContext("another-session", "run", "turn")).isEmpty());
            assertTrue(runtime.listTools(null).isEmpty());
            files.writeFile("skills/invoice/SKILL.md", skill("other", "echo"), "text/markdown");
            skill.execute(
                    context,
                    objectMapper.createObjectNode().put("path", "skills/invoice/SKILL.md"));
            assertEquals(Arrays.asList("demo__echo", "other__echo"), names(runtime, context));
        }
        try (McpRuntime restored = new McpRuntime(manager, objectMapper, activations)) {
            assertEquals(Arrays.asList("demo__echo", "other__echo"), names(restored, context));
        }
    }

    @Test
    void validatesWholeDependencySetAndRevokesPreviouslyIssuedTools() throws Exception {
        MemoryStore files = new MemoryStore();
        InMemorySkillActivationStore activations = new InMemorySkillActivationStore();
        McpConfigurationManager manager =
                new McpConfigurationManager(tempDir, "mcp.json", files, objectMapper);
        String deferred =
                config(true, null)
                        .replace("\"enabled\":true", "\"enabled\":true,\"exposure\":\"on_skill\"");
        manager.saveDatabase(deferred);
        ToolContext context = new ToolContext("session", "run", "turn");
        try (McpRuntime runtime = new McpRuntime(manager, objectMapper, activations)) {
            SkillActivationService activation =
                    new SkillActivationService(activations, () -> runtime);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            activation.activate(
                                    context,
                                    "skills/bad/SKILL.md",
                                    "global:",
                                    skill("demo", "echo, missing")));
            assertTrue(activations.find("session", "global").isEmpty());
            assertTrue(runtime.listTools(context).isEmpty());
            activation.activate(context, "skills/good/SKILL.md", "global:", skill("demo", "echo"));
            ToolDefinition issued = runtime.listTools(context).iterator().next();
            assertTrue(
                    issued.execute(context, objectMapper.createObjectNode().put("value", "ok"))
                            .getContent()
                            .contains("echo: ok"));
            manager.saveDatabase(deferred.replace("\"enabled\":true", "\"enabled\":false"));
            assertTrue(runtime.listTools(context).isEmpty());
            assertTrue(
                    issued.execute(context, objectMapper.createObjectNode())
                            .getContent()
                            .contains("no longer available"));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            activation.activate(
                                    context,
                                    "skills/good/SKILL.md",
                                    "global:",
                                    skill("demo", "echo")));
            manager.saveDatabase(
                    deferred.replace("\"enabled\":true", "\"enabled\":true,\"enabledTools\":[]"));
            assertTrue(runtime.listTools(context).isEmpty());
            manager.saveDatabase(deferred);
            assertEquals(
                    1, runtime.listTools(context).size()); // Receipt is not an authorization grant.
            assertTrue(
                    issued.execute(context, objectMapper.createObjectNode())
                            .getContent()
                            .contains("configuration changed"));
        }
    }

    @Test
    void activationIsIsolatedByProjectAndPersonalAgent() throws Exception {
        MemoryStore files = new MemoryStore();
        InMemorySkillActivationStore activations = new InMemorySkillActivationStore();
        McpConfigurationManager manager =
                new McpConfigurationManager(tempDir, "mcp.json", files, objectMapper);
        String deferred =
                config(true, null)
                        .replace("\"enabled\":true", "\"enabled\":true,\"exposure\":\"on_skill\"");
        manager.saveDatabase(deferred);
        manager.saveDatabase(KnowledgeScope.agentGlobal(), deferred);
        AgentContext project =
                new AgentContext(
                        "same-session", null, null, null, null, null, Collections.emptyMap(), "p1");
        AgentContext otherProject =
                new AgentContext(
                        "same-session", null, null, null, null, null, Collections.emptyMap(), "p2");
        AgentContext agent =
                new AgentContext(
                        "same-session",
                        null,
                        null,
                        null,
                        null,
                        null,
                        Collections.singletonMap("personalAgentId", "a1"));
        AgentContext otherAgent =
                new AgentContext(
                        "same-session",
                        null,
                        null,
                        null,
                        null,
                        null,
                        Collections.singletonMap("personalAgentId", "a2"));
        try (McpRuntime runtime = new McpRuntime(manager, objectMapper, activations)) {
            SkillActivationService activation =
                    new SkillActivationService(activations, () -> runtime);
            activation.activate(
                    project, "skills/good/SKILL.md", "project:p1", skill("demo", "echo"));
            assertEquals(1, runtime.listTools(project).size());
            assertTrue(runtime.listTools(otherProject).isEmpty());
            assertTrue(runtime.listTools(agent).isEmpty());
            activation.activate(agent, "skills/good/SKILL.md", "agent:a1", skill("demo", "echo"));
            assertEquals(1, runtime.listTools(agent).size());
            assertTrue(runtime.listTools(otherAgent).isEmpty());
            manager.saveDatabase(KnowledgeScope.project("p1"), config(false, null));
            assertTrue(runtime.listTools(project).isEmpty());
            assertEquals(1, runtime.listTools(agent).size());
        }
    }

    @Test
    void rejectsUnknownExposureMode() {
        McpServerConfig config = new McpServerConfig();
        config.setUrl(endpoint);
        config.setExposure("on_skills");
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpConfigValidator().validate("demo", config));
        config.setExposure("on_skill");
        assertEquals(
                "on_skill", new McpConfigValidator().validate("demo", config).copy().getExposure());
    }

    private String skill(String serverName, String tools) {
        return "---\nname: business\ndescription: Business workflow\ntool_dependencies:\n  - server: "
                + serverName
                + "\n    tools: ["
                + tools
                + "]\n---\nFollow the business instructions.";
    }

    private List<String> names(McpRuntime runtime, AgentContext context) {
        return runtime.listTools(context).stream()
                .map(ToolDefinition::getName)
                .sorted()
                .collect(Collectors.toList());
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
                respond(
                        exchange,
                        request,
                        "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                                + "\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}");
                return;
            }
            if ("tools/list".equals(method)) {
                respond(
                        exchange,
                        request,
                        "{\"tools\":[{\"name\":\"echo\","
                                + "\"description\":\"Echo the provided value\","
                                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                                + "\"value\":{\"type\":\"string\"}}}}]}");
                return;
            }
            String value = request.path("params").path("arguments").path("value").asText();
            respond(
                    exchange,
                    request,
                    "{\"content\":[{\"type\":\"text\",\"text\":\"echo: "
                            + value
                            + "\"}],\"isError\":false}");
        }

        private void respond(HttpExchange exchange, JsonNode request, String result)
                throws IOException {
            if (extraTool && "tools/list".equals(request.path("method").asText())) {
                JsonNode response = objectMapper.readTree(result);
                ((com.fasterxml.jackson.databind.node.ArrayNode) response.path("tools"))
                        .add(
                                objectMapper
                                        .createObjectNode()
                                        .put("name", "unused")
                                        .set(
                                                "inputSchema",
                                                objectMapper
                                                        .createObjectNode()
                                                        .put("type", "object")));
                result = response.toString();
            }
            byte[] body =
                    ("{\"jsonrpc\":\"2.0\",\"id\":"
                                    + request.path("id").asLong()
                                    + ",\"result\":"
                                    + result
                                    + "}")
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
            return file == null
                    ? Collections.<KnowledgeFile>emptyList()
                    : Collections.singletonList(file);
        }

        @Override
        public KnowledgeFile writeFile(String path, String content, String contentType) {
            return writeFile(KnowledgeScope.global(), path, content, contentType);
        }

        @Override
        public KnowledgeFile writeFile(
                KnowledgeScope scope, String path, String content, String contentType) {
            KnowledgeFile file =
                    new KnowledgeFile(
                            path,
                            KnowledgeFile.TYPE_FILE,
                            content,
                            contentType,
                            Instant.now(),
                            Instant.now());
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

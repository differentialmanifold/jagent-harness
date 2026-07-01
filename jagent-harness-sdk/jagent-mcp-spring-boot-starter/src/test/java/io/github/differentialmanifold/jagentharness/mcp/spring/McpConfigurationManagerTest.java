package io.github.differentialmanifold.jagentharness.mcp.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigurationManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void mergesDatabaseProjectAndGlobalAndKeepsRuntimeSnapshotsStable() throws Exception {
        Path configRoot = Files.createDirectories(tempDir.resolve("global"));
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.write(configRoot.resolve("mcp.json"), document(
                server("global-only", "http://global/only"),
                server("shared", "http://global/shared")).getBytes(StandardCharsets.UTF_8));
        Files.write(workspace.resolve("mcp.json"), document(
                server("project-only", "http://project/only"),
                server("shared", "http://project/shared")).getBytes(StandardCharsets.UTF_8));

        MemoryStore store = new MemoryStore();
        store.writeFile("mcp.json", document(
                server("database-only", "http://database/only"),
                server("shared", "http://database/shared")), "application/json");
        McpConfigurationManager manager = new McpConfigurationManager(
                configRoot, "mcp.json", store, new ObjectMapper());
        manager.initialize();

        McpConfigSnapshot runtime = manager.runtimeSnapshot(workspace);
        assertEquals(4, runtime.getEffectiveServers().size());
        assertEquals("database", runtime.getEffectiveServers().get("shared").getSource());
        assertEquals("http://database/shared", runtime.getEffectiveServers().get("shared").getConfig().getUrl());
        assertEquals(java.util.Arrays.asList("global", "project"),
                runtime.getEffectiveServers().get("shared").getOverriddenSources());

        Files.write(workspace.resolve("mcp.json"), document(
                server("changed", "http://changed/project")).getBytes(StandardCharsets.UTF_8));
        store.writeFile("mcp.json", document(server("changed", "http://changed/database")), "application/json");

        assertEquals(4, manager.runtimeSnapshot(workspace).getEffectiveServers().size());
        assertEquals(3, manager.currentSnapshot(workspace).getEffectiveServers().size());
        assertEquals("database", manager.currentSnapshot(workspace).getEffectiveServers().get("changed").getSource());
    }

    @Test
    void requiresEnvironmentReferenceForSensitiveHeaders() {
        McpServerConfig config = server("secure", "https://example.com/mcp").getValue();
        config.getHeaders().put("Authorization", "Bearer literal-token");

        assertThrows(IllegalArgumentException.class, () -> new McpConfigValidator().validate("secure", config));
    }

    @Test
    void replacesAndDeletesTheSingleDatabaseConfigFile() throws Exception {
        MemoryStore store = new MemoryStore();
        McpConfigurationManager manager = new McpConfigurationManager(
                tempDir, "mcp.json", store, new ObjectMapper());
        String first = document(server("first", "http://first/mcp"));
        String second = document(server("second", "http://second/mcp"));

        manager.saveDatabase(first);
        assertEquals(first + "\n", store.readFile("mcp.json").getContent());

        manager.saveDatabase(second);
        assertEquals(second + "\n", store.readFile("mcp.json").getContent());
        assertEquals(1, manager.currentSnapshot(null).getEffectiveServers().size());
        assertEquals("http://second/mcp",
                manager.currentSnapshot(null).getEffectiveServers().get("second").getConfig().getUrl());

        manager.deleteDatabase();
        assertNull(store.readFile("mcp.json"));
        assertNull(manager.currentSnapshot(null).getDatabaseConfig());
    }

    @SafeVarargs
    private final String document(Map.Entry<String, McpServerConfig>... entries) throws Exception {
        McpConfigDocument document = new McpConfigDocument();
        Map<String, McpServerConfig> servers = new LinkedHashMap<String, McpServerConfig>();
        for (Map.Entry<String, McpServerConfig> entry : entries) {
            servers.put(entry.getKey(), entry.getValue());
        }
        document.setMcpServers(servers);
        return new ObjectMapper().writeValueAsString(document);
    }

    private Map.Entry<String, McpServerConfig> server(String name, String url) {
        McpServerConfig config = new McpServerConfig();
        config.setUrl(url);
        return new java.util.AbstractMap.SimpleEntry<String, McpServerConfig>(name, config);
    }

    private static class MemoryStore implements KnowledgeFileStore {
        private KnowledgeFile file;

        @Override
        public KnowledgeFile readFile(String path) {
            return file;
        }

        @Override
        public List<KnowledgeFile> listFiles(String prefix) {
            return file == null ? java.util.Collections.<KnowledgeFile>emptyList() : java.util.Collections.singletonList(file);
        }

        @Override
        public KnowledgeFile writeFile(String path, String content, String contentType) {
            file = new KnowledgeFile(path, KnowledgeFile.TYPE_FILE, content, contentType, Instant.now(), Instant.now());
            return file;
        }

        @Override
        public void deleteFile(String path) {
            file = null;
        }
    }
}

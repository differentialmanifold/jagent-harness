package io.github.differentialmanifold.jagentharness.mcp.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigurationManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void mergesProjectAndGlobalDatabaseScopesAndKeepsRuntimeSnapshotsStable() throws Exception {
        MemoryStore store = new MemoryStore();
        store.writeFile(KnowledgeScope.global(), "mcp.json", document(
                server("global-only", "http://global/only"),
                server("shared", "http://global/shared")), "application/json");
        store.writeFile(KnowledgeScope.project("project-1"), "mcp.json", document(
                server("project-only", "http://project/only"),
                server("shared", "http://project/shared")), "application/json");
        McpConfigurationManager manager = new McpConfigurationManager(
                tempDir, "mcp.json", store, new ObjectMapper());
        manager.initialize();

        McpConfigSnapshot runtime = manager.runtimeSnapshot("project-1");
        assertEquals(3, runtime.getEffectiveServers().size());
        assertEquals("project", runtime.getEffectiveServers().get("shared").getSource());
        assertEquals("http://project/shared", runtime.getEffectiveServers().get("shared").getConfig().getUrl());
        assertEquals(java.util.Collections.singletonList("global"),
                runtime.getEffectiveServers().get("shared").getOverriddenSources());

        store.writeFile(KnowledgeScope.global(), "mcp.json",
                document(server("changed-global", "http://changed/global")), "application/json");
        store.writeFile(KnowledgeScope.project("project-1"), "mcp.json",
                document(server("changed-project", "http://changed/project")), "application/json");

        assertEquals(3, manager.runtimeSnapshot("project-1").getEffectiveServers().size());
        assertEquals(2, manager.currentSnapshot("project-1", KnowledgeScope.project("project-1"))
                .getEffectiveServers().size());
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
        assertEquals(1, manager.currentSnapshot(null, KnowledgeScope.global()).getEffectiveServers().size());
        assertEquals("http://second/mcp",
                manager.currentSnapshot(null, KnowledgeScope.global()).getEffectiveServers().get("second").getConfig().getUrl());

        manager.deleteDatabase();
        assertNull(store.readFile("mcp.json"));
        assertNull(manager.currentSnapshot(null, KnowledgeScope.global()).getDatabaseConfig());
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
        private final Map<String, KnowledgeFile> files = new LinkedHashMap<String, KnowledgeFile>();

        @Override
        public KnowledgeFile readFile(String path) {
            return readFile(KnowledgeScope.global(), path);
        }

        @Override
        public KnowledgeFile readFile(KnowledgeScope scope, String path) {
            return files.get(key(scope, path));
        }

        @Override
        public List<KnowledgeFile> listFiles(String prefix) {
            KnowledgeFile file = readFile(prefix);
            return file == null ? java.util.Collections.<KnowledgeFile>emptyList() : java.util.Collections.singletonList(file);
        }

        @Override
        public KnowledgeFile writeFile(String path, String content, String contentType) {
            return writeFile(KnowledgeScope.global(), path, content, contentType);
        }

        @Override
        public KnowledgeFile writeFile(KnowledgeScope scope, String path, String content, String contentType) {
            KnowledgeFile file = new KnowledgeFile(path, KnowledgeFile.TYPE_FILE, content, contentType, Instant.now(), Instant.now());
            files.put(key(scope, path), file);
            return file;
        }

        @Override
        public void deleteFile(String path) {
            deleteFile(KnowledgeScope.global(), path);
        }

        @Override
        public void deleteFile(KnowledgeScope scope, String path) {
            files.remove(key(scope, path));
        }

        private String key(KnowledgeScope scope, String path) {
            return scope.getType() + ":" + scope.getId() + ":" + path;
        }
    }
}

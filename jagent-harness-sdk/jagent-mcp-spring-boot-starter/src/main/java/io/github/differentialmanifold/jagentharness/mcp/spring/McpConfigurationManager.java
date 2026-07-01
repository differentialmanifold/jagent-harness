package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;

public class McpConfigurationManager {

    public static final String SOURCE_GLOBAL = "global";
    public static final String SOURCE_PROJECT = "project";
    public static final String SOURCE_DATABASE = "database";

    private final Path configRoot;
    private final String configFile;
    private final KnowledgeFileStore knowledgeFileStore;
    private final ObjectMapper objectMapper;
    private final McpConfigValidator validator = new McpConfigValidator();
    private final Map<String, Map<String, McpServerConfig>> projectSnapshots =
            new LinkedHashMap<String, Map<String, McpServerConfig>>();
    private Map<String, McpServerConfig> startupGlobal = Collections.emptyMap();
    private Map<String, McpServerConfig> startupDatabase = Collections.emptyMap();
    private String startupDatabaseConfig;

    public McpConfigurationManager(Path configRoot,
                                   String configFile,
                                   KnowledgeFileStore knowledgeFileStore,
                                   ObjectMapper objectMapper) {
        this.configRoot = configRoot;
        this.configFile = configFile == null || configFile.trim().isEmpty() ? "mcp.json" : configFile.trim();
        this.knowledgeFileStore = knowledgeFileStore;
        this.objectMapper = objectMapper;
    }

    public synchronized void initialize() {
        startupGlobal = readFile(configRoot == null ? null : configRoot.resolve(configFile));
        KnowledgeFile database = readDatabaseFile();
        startupDatabaseConfig = database == null ? null : database.getContent();
        startupDatabase = readContent(startupDatabaseConfig, "database " + configFile);
    }

    public synchronized McpConfigSnapshot runtimeSnapshot(Path workspaceRoot) {
        Map<String, McpServerConfig> project = projectSnapshot(workspaceRoot);
        return merge(startupGlobal, project, startupDatabase, startupDatabaseConfig);
    }

    public McpConfigSnapshot currentSnapshot(Path workspaceRoot) {
        Map<String, McpServerConfig> global = readFile(configRoot == null ? null : configRoot.resolve(configFile));
        Map<String, McpServerConfig> project = readFile(workspaceRoot == null ? null : workspaceRoot.resolve(configFile));
        KnowledgeFile database = readDatabaseFile();
        Map<String, McpServerConfig> databaseServers = readContent(
                database == null ? null : database.getContent(),
                "database " + configFile);
        return merge(global, project, databaseServers, database == null ? null : database.getContent());
    }

    public KnowledgeFile saveDatabase(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("MCP configuration content is required");
        }
        Map<String, McpServerConfig> servers = readContent(content, "database " + configFile);
        for (Map.Entry<String, McpServerConfig> entry : servers.entrySet()) {
            validator.validate(entry.getKey(), entry.getValue());
        }
        if (knowledgeFileStore == null) {
            throw new IllegalStateException("KnowledgeFileStore is required for database MCP configuration");
        }
        String storedContent = content.endsWith("\n") ? content : content + "\n";
        return knowledgeFileStore.writeFile(configFile, storedContent, "application/json");
    }

    public void deleteDatabase() {
        if (knowledgeFileStore == null) {
            throw new IllegalStateException("KnowledgeFileStore is required for database MCP configuration");
        }
        knowledgeFileStore.deleteFile(configFile);
    }

    public McpServerConfig resolve(String name, McpServerConfig config) {
        return validator.validateAndResolve(name, config);
    }

    private synchronized Map<String, McpServerConfig> projectSnapshot(Path workspaceRoot) {
        if (workspaceRoot == null) {
            return Collections.emptyMap();
        }
        String key = workspaceRoot.toAbsolutePath().normalize().toString();
        Map<String, McpServerConfig> existing = projectSnapshots.get(key);
        if (existing != null) {
            return existing;
        }
        Map<String, McpServerConfig> loaded = readFile(workspaceRoot.resolve(configFile));
        projectSnapshots.put(key, loaded);
        return loaded;
    }

    private McpConfigSnapshot merge(Map<String, McpServerConfig> global,
                                    Map<String, McpServerConfig> project,
                                    Map<String, McpServerConfig> database,
                                    String databaseConfig) {
        Map<String, McpServerConfig> configs = new LinkedHashMap<String, McpServerConfig>();
        Map<String, String> sources = new LinkedHashMap<String, String>();
        Map<String, List<String>> overridden = new LinkedHashMap<String, List<String>>();
        mergeSource(configs, sources, overridden, global, SOURCE_GLOBAL);
        mergeSource(configs, sources, overridden, project, SOURCE_PROJECT);
        mergeSource(configs, sources, overridden, database, SOURCE_DATABASE);

        Map<String, McpConfigEntry> effective = new LinkedHashMap<String, McpConfigEntry>();
        for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
            effective.put(entry.getKey(), new McpConfigEntry(
                    entry.getValue().copy(),
                    sources.get(entry.getKey()),
                    overridden.get(entry.getKey())));
        }
        return new McpConfigSnapshot(effective, databaseConfig);
    }

    private void mergeSource(Map<String, McpServerConfig> configs,
                             Map<String, String> sources,
                             Map<String, List<String>> overridden,
                             Map<String, McpServerConfig> additions,
                             String source) {
        for (Map.Entry<String, McpServerConfig> entry : additions.entrySet()) {
            String previousSource = sources.get(entry.getKey());
            if (previousSource != null) {
                List<String> history = overridden.get(entry.getKey());
                if (history == null) {
                    history = new ArrayList<String>();
                    overridden.put(entry.getKey(), history);
                }
                history.add(previousSource);
            }
            configs.put(entry.getKey(), entry.getValue().copy());
            sources.put(entry.getKey(), source);
        }
    }

    private Map<String, McpServerConfig> readFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Collections.emptyMap();
        }
        try {
            return readContent(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), path.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read MCP configuration " + path, e);
        }
    }

    private Map<String, McpServerConfig> readContent(String content, String source) {
        if (content == null || content.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            McpConfigDocument document = objectMapper.readValue(content, McpConfigDocument.class);
            return copy(document.getMcpServers());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse MCP configuration from " + source, e);
        }
    }

    private KnowledgeFile readDatabaseFile() {
        return knowledgeFileStore == null ? null : knowledgeFileStore.readFile(configFile);
    }

    private Map<String, McpServerConfig> copy(Map<String, McpServerConfig> source) {
        Map<String, McpServerConfig> copy = new LinkedHashMap<String, McpServerConfig>();
        if (source != null) {
            for (Map.Entry<String, McpServerConfig> entry : source.entrySet()) {
                copy.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().copy());
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}

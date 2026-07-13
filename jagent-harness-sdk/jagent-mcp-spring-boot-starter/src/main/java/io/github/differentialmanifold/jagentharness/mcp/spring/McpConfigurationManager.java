package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;

public class McpConfigurationManager {

    public static final String SOURCE_GLOBAL = "global";
    public static final String SOURCE_PROJECT = "project";

    private final String configFile;
    private final KnowledgeFileStore knowledgeFileStore;
    private final ObjectMapper objectMapper;
    private final McpConfigValidator validator = new McpConfigValidator();

    public McpConfigurationManager(Path configRoot,
                                   String configFile,
                                   KnowledgeFileStore knowledgeFileStore,
                                   ObjectMapper objectMapper) {
        this.configFile = configFile == null || configFile.trim().isEmpty() ? "mcp.json" : configFile.trim();
        this.knowledgeFileStore = knowledgeFileStore;
        this.objectMapper = objectMapper;
    }

    public synchronized void initialize() {
        // Configuration is read from the shared store when each tool scope is resolved.
    }

    public synchronized McpConfigSnapshot runtimeSnapshot(String projectId) {
        String normalizedProjectId = projectId == null ? "" : projectId.trim();
        String globalContent = configContent(KnowledgeScope.global());
        String projectContent = normalizedProjectId.isEmpty()
                ? null
                : configContent(KnowledgeScope.project(normalizedProjectId));
        return merge(
                readContent(globalContent, "global database " + configFile),
                readContent(projectContent, "project database " + configFile),
                fingerprint(normalizedProjectId, globalContent, projectContent));
    }

    public McpScopeConfigSnapshot scopeSnapshot(KnowledgeScope scope) {
        KnowledgeScope selectedScope = scope == null ? KnowledgeScope.global() : scope;
        String content = configContent(selectedScope);
        Map<String, McpServerConfig> configs = readContent(
                content,
                selectedScope.getType() + " database " + configFile);
        String source = selectedScope.isGlobal() ? SOURCE_GLOBAL : SOURCE_PROJECT;
        Map<String, McpConfigEntry> servers = new LinkedHashMap<String, McpConfigEntry>();
        for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
            servers.put(entry.getKey(), new McpConfigEntry(
                    entry.getValue().copy(),
                    source,
                    Collections.<String>emptyList()));
        }
        return new McpScopeConfigSnapshot(servers, content);
    }

    public KnowledgeFile saveDatabase(String content) {
        return saveDatabase(KnowledgeScope.global(), content);
    }

    public KnowledgeFile saveDatabase(KnowledgeScope scope, String content) {
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
        return knowledgeFileStore.writeFile(scope, configFile, storedContent, "application/json");
    }

    public void deleteDatabase() {
        deleteDatabase(KnowledgeScope.global());
    }

    public void deleteDatabase(KnowledgeScope scope) {
        if (knowledgeFileStore == null) {
            throw new IllegalStateException("KnowledgeFileStore is required for database MCP configuration");
        }
        knowledgeFileStore.deleteFile(scope, configFile);
    }

    public McpServerConfig resolve(String name, McpServerConfig config) {
        return validator.validateAndResolve(name, config);
    }

    private McpConfigSnapshot merge(Map<String, McpServerConfig> global,
                                    Map<String, McpServerConfig> project,
                                    String fingerprint) {
        Map<String, McpServerConfig> configs = new LinkedHashMap<String, McpServerConfig>();
        Map<String, String> sources = new LinkedHashMap<String, String>();
        Map<String, List<String>> overridden = new LinkedHashMap<String, List<String>>();
        mergeSource(configs, sources, overridden, global, SOURCE_GLOBAL);
        mergeSource(configs, sources, overridden, project, SOURCE_PROJECT);

        Map<String, McpConfigEntry> effective = new LinkedHashMap<String, McpConfigEntry>();
        for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
            effective.put(entry.getKey(), new McpConfigEntry(
                    entry.getValue().copy(),
                    sources.get(entry.getKey()),
                    overridden.get(entry.getKey())));
        }
        return new McpConfigSnapshot(effective, fingerprint);
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

    private String configContent(KnowledgeScope scope) {
        if (knowledgeFileStore == null) {
            return null;
        }
        KnowledgeFile file = knowledgeFileStore.readFile(scope == null ? KnowledgeScope.global() : scope, configFile);
        return file == null ? null : file.getContent();
    }

    private String fingerprint(String projectId, String globalContent, String projectContent) {
        String value = "mcp-runtime-v1\u0000"
                + projectId + "\u0000"
                + (globalContent == null ? "<absent>" : globalContent) + "\u0000"
                + (projectContent == null ? "<absent>" : projectContent);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
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

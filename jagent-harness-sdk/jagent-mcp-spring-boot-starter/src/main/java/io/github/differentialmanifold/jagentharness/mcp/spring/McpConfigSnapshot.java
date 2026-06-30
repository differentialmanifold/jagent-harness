package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;

public class McpConfigSnapshot {

    private final Map<String, McpConfigEntry> effectiveServers;
    private final Map<String, McpServerConfig> databaseServers;
    private final String databaseContentHash;

    McpConfigSnapshot(Map<String, McpConfigEntry> effectiveServers,
                      Map<String, McpServerConfig> databaseServers,
                      String databaseContentHash) {
        this.effectiveServers = Collections.unmodifiableMap(new LinkedHashMap<String, McpConfigEntry>(effectiveServers));
        this.databaseServers = Collections.unmodifiableMap(new LinkedHashMap<String, McpServerConfig>(databaseServers));
        this.databaseContentHash = databaseContentHash;
    }

    public Map<String, McpConfigEntry> getEffectiveServers() {
        return effectiveServers;
    }

    public Map<String, McpServerConfig> getDatabaseServers() {
        return databaseServers;
    }

    public String getDatabaseContentHash() {
        return databaseContentHash;
    }
}

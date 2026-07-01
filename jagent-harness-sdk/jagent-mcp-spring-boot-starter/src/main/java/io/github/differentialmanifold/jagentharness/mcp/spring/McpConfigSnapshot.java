package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class McpConfigSnapshot {

    private final Map<String, McpConfigEntry> effectiveServers;
    private final String databaseConfig;

    McpConfigSnapshot(Map<String, McpConfigEntry> effectiveServers,
                      String databaseConfig) {
        this.effectiveServers = Collections.unmodifiableMap(new LinkedHashMap<String, McpConfigEntry>(effectiveServers));
        this.databaseConfig = databaseConfig;
    }

    public Map<String, McpConfigEntry> getEffectiveServers() {
        return effectiveServers;
    }

    public String getDatabaseConfig() {
        return databaseConfig;
    }
}

package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class McpScopeConfigSnapshot {

    private final Map<String, McpConfigEntry> servers;
    private final String databaseConfig;

    McpScopeConfigSnapshot(Map<String, McpConfigEntry> servers, String databaseConfig) {
        this.servers = Collections.unmodifiableMap(new LinkedHashMap<String, McpConfigEntry>(servers));
        this.databaseConfig = databaseConfig;
    }

    public Map<String, McpConfigEntry> getServers() {
        return servers;
    }

    public String getDatabaseConfig() {
        return databaseConfig;
    }
}

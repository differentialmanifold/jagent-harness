package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class McpConfigSnapshot {

    private final Map<String, McpConfigEntry> effectiveServers;
    private final String databaseConfig;
    private final String fingerprint;

    McpConfigSnapshot(Map<String, McpConfigEntry> effectiveServers,
                      String databaseConfig,
                      String fingerprint) {
        this.effectiveServers = Collections.unmodifiableMap(new LinkedHashMap<String, McpConfigEntry>(effectiveServers));
        this.databaseConfig = databaseConfig;
        this.fingerprint = fingerprint;
    }

    public Map<String, McpConfigEntry> getEffectiveServers() {
        return effectiveServers;
    }

    public String getDatabaseConfig() {
        return databaseConfig;
    }

    public String getFingerprint() {
        return fingerprint;
    }
}

package io.github.differentialmanifold.jagentharness.spring.web.dto;

import java.util.List;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;

public class McpConfigResponse {

    private final String databaseContentHash;
    private final Map<String, McpServerConfig> databaseServers;
    private final List<McpServerResponse> servers;
    private final boolean restartRequired;

    public McpConfigResponse(String databaseContentHash,
                             Map<String, McpServerConfig> databaseServers,
                             List<McpServerResponse> servers,
                             boolean restartRequired) {
        this.databaseContentHash = databaseContentHash;
        this.databaseServers = databaseServers;
        this.servers = servers;
        this.restartRequired = restartRequired;
    }

    public String getDatabaseContentHash() {
        return databaseContentHash;
    }

    public Map<String, McpServerConfig> getDatabaseServers() {
        return databaseServers;
    }

    public List<McpServerResponse> getServers() {
        return servers;
    }

    public boolean isRestartRequired() {
        return restartRequired;
    }
}

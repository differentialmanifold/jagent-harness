package io.github.differentialmanifold.jagentharness.spring.web.dto;

import java.util.List;

public class McpConfigResponse {

    private final String databaseConfig;
    private final List<McpServerResponse> servers;
    private final boolean restartRequired;

    public McpConfigResponse(String databaseConfig,
                             List<McpServerResponse> servers,
                             boolean restartRequired) {
        this.databaseConfig = databaseConfig;
        this.servers = servers;
        this.restartRequired = restartRequired;
    }

    public String getDatabaseConfig() {
        return databaseConfig;
    }

    public List<McpServerResponse> getServers() {
        return servers;
    }

    public boolean isRestartRequired() {
        return restartRequired;
    }
}

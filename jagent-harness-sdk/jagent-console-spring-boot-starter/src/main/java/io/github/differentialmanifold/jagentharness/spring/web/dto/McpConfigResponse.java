package io.github.differentialmanifold.jagentharness.spring.web.dto;

import java.util.List;

public class McpConfigResponse {

    private final String databaseConfig;
    private final List<McpServerResponse> servers;

    public McpConfigResponse(String databaseConfig,
                             List<McpServerResponse> servers) {
        this.databaseConfig = databaseConfig;
        this.servers = servers;
    }

    public String getDatabaseConfig() {
        return databaseConfig;
    }

    public List<McpServerResponse> getServers() {
        return servers;
    }
}

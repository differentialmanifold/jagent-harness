package io.github.differentialmanifold.jagentharness.spring.web.dto;

import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;

public class McpTestRequest {

    private String name;
    private McpServerConfig config;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public McpServerConfig getConfig() {
        return config;
    }

    public void setConfig(McpServerConfig config) {
        this.config = config;
    }
}

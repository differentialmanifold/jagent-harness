package io.github.differentialmanifold.jagentharness.spring.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;

public class McpToolCallRequest {

    private String name;
    private McpServerConfig config;
    private String toolName;
    private JsonNode arguments;

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

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public JsonNode getArguments() {
        return arguments;
    }

    public void setArguments(JsonNode arguments) {
        this.arguments = arguments;
    }
}

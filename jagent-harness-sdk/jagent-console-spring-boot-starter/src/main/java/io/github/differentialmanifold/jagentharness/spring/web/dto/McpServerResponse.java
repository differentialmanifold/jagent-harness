package io.github.differentialmanifold.jagentharness.spring.web.dto;

import java.util.List;

import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;

public class McpServerResponse {

    private final String name;
    private final McpServerConfig config;
    private final String source;
    private final List<String> overriddenSources;
    private final String status;
    private final String error;
    private final String protocolVersion;
    private final List<String> tools;
    private final List<String> availableTools;

    public McpServerResponse(String name,
                             McpServerConfig config,
                             String source,
                             List<String> overriddenSources,
                             String status,
                             String error,
                             String protocolVersion,
                             List<String> tools) {
        this(name, config, source, overriddenSources, status, error, protocolVersion, tools, tools);
    }

    public McpServerResponse(String name,
                             McpServerConfig config,
                             String source,
                             List<String> overriddenSources,
                             String status,
                             String error,
                             String protocolVersion,
                             List<String> tools,
                             List<String> availableTools) {
        this.name = name;
        this.config = config;
        this.source = source;
        this.overriddenSources = overriddenSources;
        this.status = status;
        this.error = error;
        this.protocolVersion = protocolVersion;
        this.tools = tools;
        this.availableTools = availableTools;
    }

    public String getName() {
        return name;
    }

    public McpServerConfig getConfig() {
        return config;
    }

    public String getSource() {
        return source;
    }

    public List<String> getOverriddenSources() {
        return overriddenSources;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public List<String> getTools() {
        return tools;
    }

    public List<String> getAvailableTools() {
        return availableTools;
    }
}

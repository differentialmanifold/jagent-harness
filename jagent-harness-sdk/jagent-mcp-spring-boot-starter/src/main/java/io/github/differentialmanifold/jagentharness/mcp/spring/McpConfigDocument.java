package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;

public class McpConfigDocument {

    private Map<String, McpServerConfig> mcpServers = new LinkedHashMap<String, McpServerConfig>();

    public Map<String, McpServerConfig> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, McpServerConfig> mcpServers) {
        this.mcpServers = mcpServers == null
                ? new LinkedHashMap<String, McpServerConfig>()
                : new LinkedHashMap<String, McpServerConfig>(mcpServers);
    }
}

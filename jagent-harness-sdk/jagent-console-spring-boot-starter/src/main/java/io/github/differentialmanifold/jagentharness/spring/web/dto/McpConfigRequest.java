package io.github.differentialmanifold.jagentharness.spring.web.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;

public class McpConfigRequest {

    private String expectedContentHash;
    private Map<String, McpServerConfig> mcpServers = new LinkedHashMap<String, McpServerConfig>();

    public String getExpectedContentHash() {
        return expectedContentHash;
    }

    public void setExpectedContentHash(String expectedContentHash) {
        this.expectedContentHash = expectedContentHash;
    }

    public Map<String, McpServerConfig> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, McpServerConfig> mcpServers) {
        this.mcpServers = mcpServers == null
                ? new LinkedHashMap<String, McpServerConfig>()
                : new LinkedHashMap<String, McpServerConfig>(mcpServers);
    }
}

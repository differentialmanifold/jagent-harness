package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;

public class McpConfigEntry {

    private final McpServerConfig config;
    private final String source;
    private final List<String> overriddenSources;

    McpConfigEntry(McpServerConfig config, String source, List<String> overriddenSources) {
        this.config = config;
        this.source = source;
        this.overriddenSources = overriddenSources == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(overriddenSources));
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
}

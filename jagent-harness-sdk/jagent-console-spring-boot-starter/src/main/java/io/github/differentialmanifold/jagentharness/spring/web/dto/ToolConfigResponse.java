package io.github.differentialmanifold.jagentharness.spring.web.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ToolConfigResponse {

    private final boolean configured;
    private final List<String> enabledTools;
    private final List<ToolInfoResponse> tools;

    public ToolConfigResponse(boolean configured,
                              List<String> enabledTools,
                              List<ToolInfoResponse> tools) {
        this.configured = configured;
        this.enabledTools = Collections.unmodifiableList(new ArrayList<String>(enabledTools));
        this.tools = Collections.unmodifiableList(new ArrayList<ToolInfoResponse>(tools));
    }

    public boolean isConfigured() {
        return configured;
    }

    public List<String> getEnabledTools() {
        return enabledTools;
    }

    public List<ToolInfoResponse> getTools() {
        return tools;
    }
}

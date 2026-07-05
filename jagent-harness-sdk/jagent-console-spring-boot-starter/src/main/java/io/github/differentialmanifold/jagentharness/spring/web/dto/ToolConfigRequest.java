package io.github.differentialmanifold.jagentharness.spring.web.dto;

import java.util.List;

public class ToolConfigRequest {

    private List<String> enabledTools;

    public List<String> getEnabledTools() {
        return enabledTools;
    }

    public void setEnabledTools(List<String> enabledTools) {
        this.enabledTools = enabledTools;
    }
}

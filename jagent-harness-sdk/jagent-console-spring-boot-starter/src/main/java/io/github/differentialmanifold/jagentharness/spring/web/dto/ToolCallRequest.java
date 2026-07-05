package io.github.differentialmanifold.jagentharness.spring.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public class ToolCallRequest {

    private String sessionId;
    private String toolName;
    private JsonNode arguments;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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

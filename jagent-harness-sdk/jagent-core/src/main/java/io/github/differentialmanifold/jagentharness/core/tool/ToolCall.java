package io.github.differentialmanifold.jagentharness.core.tool;

public class ToolCall {

    private String toolCallId;
    private String name;
    private String argumentsJson;

    public ToolCall() {
    }

    public ToolCall(String toolCallId, String name, String argumentsJson) {
        this.toolCallId = toolCallId;
        this.name = name;
        this.argumentsJson = argumentsJson;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public void setArgumentsJson(String argumentsJson) {
        this.argumentsJson = argumentsJson;
    }
}

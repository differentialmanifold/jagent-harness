package io.github.differentialmanifold.jagentharness.core.provider;

import java.util.ArrayList;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;

public class ModelResponse {

    private String content;
    private String reasoningContent;
    private List<ToolCall> toolCalls = new ArrayList<ToolCall>();
    private String rawJson;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getRawJson() {
        return rawJson;
    }

    public void setRawJson(String rawJson) {
        this.rawJson = rawJson;
    }
}

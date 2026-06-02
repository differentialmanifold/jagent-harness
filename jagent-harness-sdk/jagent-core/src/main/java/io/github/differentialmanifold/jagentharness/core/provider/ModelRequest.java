package io.github.differentialmanifold.jagentharness.core.provider;

import java.util.Collection;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;

public class ModelRequest {

    private String model;
    private String systemPrompt;
    private List<AgentMessage> messages;
    private Collection<ToolDefinition> tools;
    private Double temperature;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public List<AgentMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<AgentMessage> messages) {
        this.messages = messages;
    }

    public Collection<ToolDefinition> getTools() {
        return tools;
    }

    public void setTools(Collection<ToolDefinition> tools) {
        this.tools = tools;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }
}

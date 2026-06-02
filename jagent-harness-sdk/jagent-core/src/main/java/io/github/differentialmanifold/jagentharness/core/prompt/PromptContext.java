package io.github.differentialmanifold.jagentharness.core.prompt;

import java.util.Collection;
import java.util.Collections;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;

public class PromptContext {

    private final Collection<ToolDefinition> tools;
    private final AgentContext agentContext;

    public PromptContext(Collection<ToolDefinition> tools, AgentContext agentContext) {
        this.tools = tools == null ? Collections.<ToolDefinition>emptyList() : tools;
        this.agentContext = agentContext;
    }

    public Collection<ToolDefinition> getTools() {
        return tools;
    }

    public AgentContext getAgentContext() {
        return agentContext;
    }
}

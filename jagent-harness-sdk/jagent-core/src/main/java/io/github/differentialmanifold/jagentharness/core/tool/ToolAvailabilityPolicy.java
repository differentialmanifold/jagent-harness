package io.github.differentialmanifold.jagentharness.core.tool;

import java.util.Collection;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;

public interface ToolAvailabilityPolicy {

    Collection<ToolDefinition> filter(Collection<ToolDefinition> tools, AgentContext context);
}

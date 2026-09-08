package io.github.differentialmanifold.jagentharness.core.tool;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import java.util.Collection;

public interface ToolAvailabilityPolicy {

    Collection<ToolDefinition> filter(Collection<ToolDefinition> tools, AgentContext context);
}

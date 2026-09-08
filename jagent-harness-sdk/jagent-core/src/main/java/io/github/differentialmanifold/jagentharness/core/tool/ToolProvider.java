package io.github.differentialmanifold.jagentharness.core.tool;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import java.util.Collection;

public interface ToolProvider {

    Collection<ToolDefinition> listTools(AgentContext context);
}

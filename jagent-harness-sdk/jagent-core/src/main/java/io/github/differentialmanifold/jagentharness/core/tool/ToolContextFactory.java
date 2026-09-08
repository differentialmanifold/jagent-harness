package io.github.differentialmanifold.jagentharness.core.tool;

import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;

public interface ToolContextFactory {

    ToolContext create(SessionRecord session, String runId, String turnId, AgentRunOptions options);
}

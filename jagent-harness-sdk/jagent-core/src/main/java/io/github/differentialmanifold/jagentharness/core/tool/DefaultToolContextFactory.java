package io.github.differentialmanifold.jagentharness.core.tool;

import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;

public class DefaultToolContextFactory implements ToolContextFactory {

    private final AgentSettings settings;

    public DefaultToolContextFactory() {
        this(null);
    }

    public DefaultToolContextFactory(AgentSettings settings) {
        this.settings = settings;
    }

    @Override
    public ToolContext create(
            SessionRecord session, String runId, String turnId, AgentRunOptions options) {
        AgentRunOptions effectiveOptions = options == null ? AgentRunOptions.empty() : options;
        ToolContext context =
                new ToolContext(
                        session == null ? null : session.getSessionId(),
                        runId,
                        turnId,
                        effectiveOptions.getTraceId(),
                        null,
                        settings == null ? null : settings.getConfigRoot(),
                        effectiveOptions.getAttributes(),
                        effectiveOptions.getStopSignal(),
                        effectiveOptions.getApprovalMode(),
                        effectiveOptions.getApprovalHandler(),
                        null,
                        null,
                        session == null ? null : session.getProjectId());
        context.setClientCapabilities(effectiveOptions.getClientCapabilities());
        return context;
    }
}

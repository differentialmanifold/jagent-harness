package io.github.differentialmanifold.jagentharness.core.tool;

import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;

public class DefaultToolContextFactory implements ToolContextFactory {

    private final AgentSettings settings;

    public DefaultToolContextFactory() {
        this(null);
    }

    public DefaultToolContextFactory(AgentSettings settings) {
        this.settings = settings;
    }

    @Override
    public ToolContext create(SessionRecord session, String turnId, AgentRunOptions options) {
        AgentRunOptions effectiveOptions = options == null ? AgentRunOptions.empty() : options;
        return new ToolContext(
                session == null ? null : session.getSessionId(),
                turnId,
                effectiveOptions.getTraceId(),
                null,
                settings == null ? null : settings.getConfigRoot(),
                effectiveOptions.getAttributes());
    }
}

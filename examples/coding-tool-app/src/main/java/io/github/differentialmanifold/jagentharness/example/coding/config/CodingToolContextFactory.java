package io.github.differentialmanifold.jagentharness.example.coding.config;

import java.nio.file.Path;

import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContextFactory;
import io.github.differentialmanifold.jagentharness.example.coding.web.CodingWorkspaceService;
import org.springframework.stereotype.Component;

@Component
public class CodingToolContextFactory implements ToolContextFactory {

    private final CodingWorkspaceService workspaceService;
    private final AgentSettings settings;

    public CodingToolContextFactory(CodingWorkspaceService workspaceService, AgentSettings settings) {
        this.workspaceService = workspaceService;
        this.settings = settings;
    }

    @Override
    public ToolContext create(SessionRecord session, String runId, String turnId, AgentRunOptions options) {
        AgentRunOptions effectiveOptions = options == null ? AgentRunOptions.empty() : options;
        return new ToolContext(
                session == null ? null : session.getSessionId(),
                runId,
                turnId,
                effectiveOptions.getTraceId(),
                workspaceRoot(session),
                settings.getConfigRoot(),
                effectiveOptions.getAttributes(),
                effectiveOptions.getStopSignal(),
                effectiveOptions.getApprovalMode(),
                effectiveOptions.getApprovalHandler(),
                null,
                null,
                session == null ? null : session.getProjectId());
    }

    private Path workspaceRoot(SessionRecord session) {
        String workspacePath = session == null ? null : session.getWorkspacePath();
        return workspaceService.workspaceRoot(workspacePath);
    }
}

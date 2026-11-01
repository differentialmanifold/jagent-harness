package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.spring.web.dto.AgentContextRequest;
import java.nio.file.Path;
import java.util.*;

/** Resolve the current configuration for a project or personal conversation without running it. */
final class RuntimeContextFactory {
    static AgentContext create(
            SessionRecord session,
            AgentContextRequest request,
            AgentSettings settings,
            WorkspaceRootResolver workspaces,
            RunnerService runners,
            PersonalAgentStore personal) {
        boolean remote =
                session != null
                        && runners != null
                        && runners.binding(session.getSessionId()) != null;
        PersonalAgentStore.Conversation conversation =
                session == null || personal == null
                        ? null
                        : personal.conversation(session.getSessionId());
        Map<String, Object> attributes = new HashMap<>();
        if (conversation != null) attributes.put("personalAgentId", conversation.agentId);
        Path workspace =
                session == null || remote || conversation != null
                        ? null
                        : workspaces.resolveWorkspaceRoot(session.getWorkspacePath());
        AgentContext context =
                new AgentContext(
                        session == null ? null : session.getSessionId(),
                        null,
                        null,
                        null,
                        workspace,
                        settings.getConfigRoot(),
                        attributes,
                        session == null ? null : session.getProjectId());
        if (conversation == null && request != null)
            context.setClientCapabilities(
                    new ClientCapabilities(
                            request.getClientTools(), request.getClientInstructions()));
        if (remote && conversation == null)
            context.setClientCapabilities(runners.capabilities(session));
        return context;
    }
}

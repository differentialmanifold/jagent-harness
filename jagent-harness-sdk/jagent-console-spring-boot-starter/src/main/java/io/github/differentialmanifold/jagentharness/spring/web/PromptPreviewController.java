package io.github.differentialmanifold.jagentharness.spring.web;

import java.nio.file.Path;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptContext;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptProvider;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.spring.web.dto.AgentContextRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.PromptPreviewResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/prompt-preview")
public class PromptPreviewController {

    private final PromptProvider promptProvider;
    private final ToolRegistry toolRegistry;
    private final AgentSettings settings;
    private final SessionManager sessionManager;
    private final WorkspaceRootResolver workspaceRootResolver;

    public PromptPreviewController(PromptProvider promptProvider,
                                   ToolRegistry toolRegistry,
                                   AgentSettings settings,
                                   SessionManager sessionManager,
                                   WorkspaceRootResolver workspaceRootResolver) {
        this.promptProvider = promptProvider;
        this.toolRegistry = toolRegistry;
        this.settings = settings;
        this.sessionManager = sessionManager;
        this.workspaceRootResolver = workspaceRootResolver;
    }

    @PostMapping
    public PromptPreviewResponse preview(@RequestBody(required = false) AgentContextRequest request) {
        SessionRecord session = findSession(request);
        Path workspaceRoot = session == null
                ? null
                : workspaceRootResolver.resolveWorkspaceRoot(session.getWorkspacePath());
        AgentContext context = new AgentContext(
                session == null ? null : session.getSessionId(),
                null,
                null,
                workspaceRoot,
                settings.getConfigRoot(),
                null);
        String prompt = promptProvider.buildSystemPrompt(new PromptContext(toolRegistry.all(context), context));
        return new PromptPreviewResponse(
                prompt,
                workspaceRoot == null ? null : workspaceRoot.toString());
    }

    private SessionRecord findSession(AgentContextRequest request) {
        String sessionId = request == null ? null : request.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        return sessionManager.requireSession(sessionId.trim());
    }
}

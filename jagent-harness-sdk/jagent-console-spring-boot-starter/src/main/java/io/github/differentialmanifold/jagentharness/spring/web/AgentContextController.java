package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillDescriptor;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillRegistry;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.spring.web.dto.AgentContextRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.AgentContextResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.PromptFileResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.SkillInfoResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ToolInfoResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/context")
public class AgentContextController {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RunnerService runners;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore personal;

    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final AgentSettings settings;
    private final SessionManager sessionManager;
    private final WorkspaceRootResolver workspaceRootResolver;
    private final KnowledgeFileStore knowledgeFileStore;

    public AgentContextController(
            ToolRegistry toolRegistry,
            SkillRegistry skillRegistry,
            AgentSettings settings,
            SessionManager sessionManager,
            WorkspaceRootResolver workspaceRootResolver,
            KnowledgeFileStore knowledgeFileStore) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.settings = settings;
        this.sessionManager = sessionManager;
        this.workspaceRootResolver = workspaceRootResolver;
        this.knowledgeFileStore = knowledgeFileStore;
    }

    @GetMapping
    public AgentContextResponse current() {
        return current(null);
    }

    @PostMapping
    public AgentContextResponse current(
            @RequestBody(required = false) AgentContextRequest request) {
        SessionRecord session = findSession(request);
        boolean remote =
                session != null
                        && runners != null
                        && runners.binding(session.getSessionId()) != null;
        AgentContext agentContext =
                RuntimeContextFactory.create(
                        session, request, settings, workspaceRootResolver, runners, personal);
        Path workspaceRoot = agentContext.getWorkspaceRoot();
        Path configRoot = settings.getConfigRoot();
        return new AgentContextResponse(
                tools(agentContext),
                promptFiles(agentContext),
                skills(agentContext),
                configRoot == null ? null : configRoot.toString(),
                remote
                        ? session.getWorkspacePath()
                        : workspaceRoot == null ? null : workspaceRoot.toString());
    }

    private SessionRecord findSession(AgentContextRequest request) {
        String sessionId = request == null ? null : request.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        return sessionManager.requireSession(sessionId.trim());
    }

    private List<ToolInfoResponse> tools(AgentContext context) {
        List<ToolInfoResponse> tools = new ArrayList<ToolInfoResponse>();
        for (ToolDefinition tool : toolRegistry.all(context)) {
            ToolInfoResponse info =
                    new ToolInfoResponse(
                            tool.getName(), tool.getDescription(), tool.getParametersSchema());
            info.setExecutionTarget(
                    toolRegistry.isClientTool(tool.getName(), context) ? "runner" : "server");
            tools.add(info);
        }
        return tools;
    }

    private List<PromptFileResponse> promptFiles(AgentContext context) {
        String projectId = context == null ? null : context.getProjectId();
        KnowledgeScope defaults = KnowledgeScope.globalFor(context);
        List<PromptFileResponse> files = new ArrayList<PromptFileResponse>();
        addDatabasePromptFile(
                files,
                defaults,
                "AGENTS.md",
                defaults.getType(),
                defaults.equals(KnowledgeScope.agentGlobal())
                        ? "Global rules for personal Agents."
                        : "Global rules for projects.");
        if (defaults.equals(KnowledgeScope.global())
                && projectId != null
                && !projectId.trim().isEmpty()) {
            addDatabasePromptFile(
                    files,
                    KnowledgeScope.project(projectId),
                    "AGENTS.md",
                    "project",
                    "Project database agent rules appended after global rules.");
        }
        return files;
    }

    private void addDatabasePromptFile(
            List<PromptFileResponse> files,
            KnowledgeScope scope,
            String name,
            String mode,
            String description) {
        if (knowledgeFileStore == null) {
            return;
        }
        KnowledgeFile promptFile = knowledgeFileStore.readFile(scope, name);
        if (promptFile != null
                && promptFile.getContent() != null
                && !promptFile.getContent().trim().isEmpty()) {
            files.add(new PromptFileResponse(name, promptFile.getPath(), true, mode, description));
        }
    }

    private List<SkillInfoResponse> skills(AgentContext context) {
        List<SkillInfoResponse> skills = new ArrayList<SkillInfoResponse>();
        for (SkillDescriptor skill : skillRegistry.listSkills(context)) {
            skills.add(
                    new SkillInfoResponse(
                            skill.getName(),
                            skill.getDescription(),
                            skill.getFilePath(),
                            skill.getSource() == null ? "database" : skill.getSource()));
        }
        return skills;
    }
}

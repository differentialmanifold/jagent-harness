package io.github.differentialmanifold.jagentharness.spring.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillDescriptor;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillRegistry;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.spring.web.dto.AgentContextRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.AgentContextResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.PromptFileResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.SkillInfoResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ToolInfoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/context")
public class AgentContextController {

    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final AgentSettings settings;
    private final SessionManager sessionManager;
    private final WorkspaceRootResolver workspaceRootResolver;
    private final KnowledgeFileStore knowledgeFileStore;

    public AgentContextController(ToolRegistry toolRegistry,
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
    public AgentContextResponse current(@RequestBody(required = false) AgentContextRequest request) {
        SessionRecord session = findSession(request);
        Path workspaceRoot = session == null ? null : workspaceRootResolver.resolveWorkspaceRoot(session.getWorkspacePath());
        Path configRoot = settings.getConfigRoot();
        AgentContext agentContext = new AgentContext(
                session == null ? null : session.getSessionId(),
                null,
                null,
                workspaceRoot,
                configRoot,
                null,
                session == null ? null : session.getProjectId());

        return new AgentContextResponse(
                tools(agentContext),
                promptFiles(session == null ? null : session.getProjectId()),
                skills(agentContext),
                configRoot == null ? null : configRoot.toString(),
                workspaceRoot == null ? null : workspaceRoot.toString());
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
            tools.add(new ToolInfoResponse(tool.getName(), tool.getDescription(), tool.getParametersSchema()));
        }
        return tools;
    }

    private List<PromptFileResponse> promptFiles(String projectId) {
        List<PromptFileResponse> files = new ArrayList<PromptFileResponse>();
        addDatabasePromptFile(files,
                KnowledgeScope.global(),
                "AGENTS.md",
                "global",
                "Global database agent rules.");
        if (projectId != null && !projectId.trim().isEmpty()) {
            addDatabasePromptFile(files,
                    KnowledgeScope.project(projectId),
                    "AGENTS.md",
                    "project",
                    "Project database agent rules appended after global rules.");
        }
        return files;
    }

    private void addDatabasePromptFile(List<PromptFileResponse> files,
                                       KnowledgeScope scope,
                                       String name,
                                       String mode,
                                       String description) {
        if (knowledgeFileStore == null) {
            return;
        }
        KnowledgeFile promptFile = knowledgeFileStore.readFile(scope, name);
        if (promptFile != null && promptFile.getContent() != null && !promptFile.getContent().trim().isEmpty()) {
            files.add(new PromptFileResponse(
                    name,
                    promptFile.getPath(),
                    true,
                    mode,
                    description));
        }
    }

    private List<SkillInfoResponse> skills(AgentContext context) {
        List<SkillInfoResponse> skills = new ArrayList<SkillInfoResponse>();
        for (SkillDescriptor skill : skillRegistry.listSkills(context)) {
            skills.add(new SkillInfoResponse(
                    skill.getName(),
                    skill.getDescription(),
                    skill.getFilePath(),
                    skill.getSource() == null ? "database" : skill.getSource()));
        }
        return skills;
    }
}

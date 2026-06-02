package io.github.differentialmanifold.jagentharness.spring.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
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

    public AgentContextController(ToolRegistry toolRegistry,
                                  SkillRegistry skillRegistry,
                                  AgentSettings settings,
                                  SessionManager sessionManager,
                                  WorkspaceRootResolver workspaceRootResolver) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.settings = settings;
        this.sessionManager = sessionManager;
        this.workspaceRootResolver = workspaceRootResolver;
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
                null);

        return new AgentContextResponse(
                tools(),
                promptFiles(configRoot),
                skills(agentContext, configRoot, workspaceRoot),
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

    private List<ToolInfoResponse> tools() {
        List<ToolInfoResponse> tools = new ArrayList<ToolInfoResponse>();
        for (ToolDefinition tool : toolRegistry.all()) {
            tools.add(new ToolInfoResponse(tool.getName(), tool.getDescription(), tool.getParametersSchema()));
        }
        return tools;
    }

    private List<PromptFileResponse> promptFiles(Path configRoot) {
        List<PromptFileResponse> files = new ArrayList<PromptFileResponse>();
        files.add(promptFile(
                configRoot,
                "SYSTEM.md",
                "override",
                "Overrides the built-in default system prompt when present."));
        files.add(promptFile(
                configRoot,
                "AGENTS.md",
                "append",
                "Appends additional agent rules after the system prompt when present."));
        return files;
    }

    private PromptFileResponse promptFile(Path configRoot, String name, String mode, String description) {
        Path path = configRoot == null ? null : configRoot.resolve(name).toAbsolutePath().normalize();
        boolean exists = path != null && Files.isRegularFile(path);
        return new PromptFileResponse(name, path == null ? null : path.toString(), exists, mode, description);
    }

    private List<SkillInfoResponse> skills(AgentContext context, Path configRoot, Path workspaceRoot) {
        List<SkillInfoResponse> skills = new ArrayList<SkillInfoResponse>();
        for (SkillDescriptor skill : skillRegistry.listSkills(context)) {
            skills.add(new SkillInfoResponse(
                    skill.getName(),
                    skill.getDescription(),
                    skill.getFilePath(),
                    skill.getDirectoryPath(),
                    skillScope(skill, configRoot, workspaceRoot)));
        }
        return skills;
    }

    private String skillScope(SkillDescriptor skill, Path configRoot, Path workspaceRoot) {
        Path filePath = skill.getFilePath() == null ? null : java.nio.file.Paths.get(skill.getFilePath()).toAbsolutePath().normalize();
        if (filePath != null && workspaceRoot != null && filePath.startsWith(workspaceRoot.toAbsolutePath().normalize())) {
            return "project";
        }
        if (filePath != null && configRoot != null && filePath.startsWith(configRoot.toAbsolutePath().normalize())) {
            return "global";
        }
        return "provider";
    }

}

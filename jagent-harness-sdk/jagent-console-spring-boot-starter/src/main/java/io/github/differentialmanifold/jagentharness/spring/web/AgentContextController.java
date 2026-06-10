package io.github.differentialmanifold.jagentharness.spring.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptBinding;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptBindingStore;
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
    private final PromptBindingStore promptBindingStore;

    public AgentContextController(ToolRegistry toolRegistry,
                                  SkillRegistry skillRegistry,
                                  AgentSettings settings,
                                  SessionManager sessionManager,
                                  WorkspaceRootResolver workspaceRootResolver,
                                  KnowledgeFileStore knowledgeFileStore,
                                  PromptBindingStore promptBindingStore) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.settings = settings;
        this.sessionManager = sessionManager;
        this.workspaceRootResolver = workspaceRootResolver;
        this.knowledgeFileStore = knowledgeFileStore;
        this.promptBindingStore = promptBindingStore;
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
                promptFiles(configRoot, workspaceRoot),
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

    private List<PromptFileResponse> promptFiles(Path configRoot, Path workspaceRoot) {
        List<PromptFileResponse> files = new ArrayList<PromptFileResponse>();
        addPromptFile(files,
                configRoot,
                "AGENTS.md",
                "append",
                "Global agent rules appended after the built-in system prompt.");
        addPromptFile(files,
                workspaceRoot,
                "AGENTS.md",
                "append",
                "Project agent rules appended after global agent rules.");
        addDatabasePromptFile(files,
                "AGENTS.md",
                "append",
                "Database agent rules appended after file-based agent rules.");
        return files;
    }

    private void addPromptFile(List<PromptFileResponse> files,
                               Path root,
                               String name,
                               String mode,
                               String description) {
        Path path = root == null ? null : root.resolve(name).toAbsolutePath().normalize();
        if (hasReadableContent(path)) {
            files.add(new PromptFileResponse(name, path.toString(), true, mode, description));
        }
    }

    private void addDatabasePromptFile(List<PromptFileResponse> files,
                                       String name,
                                       String mode,
                                       String description) {
        if (knowledgeFileStore == null) {
            return;
        }
        boolean addedBinding = false;
        if (promptBindingStore != null) {
            for (PromptBinding binding : promptBindingStore.listBindings(name)) {
                KnowledgeFile promptFile = binding == null ? null : knowledgeFileStore.readFile(binding.getFilePath());
                if (promptFile != null && promptFile.getContent() != null && !promptFile.getContent().trim().isEmpty()) {
                    files.add(new PromptFileResponse(
                            name,
                            promptFile.getPath(),
                            true,
                            mode,
                            description));
                    addedBinding = true;
                }
            }
        }
        if (!addedBinding) {
            KnowledgeFile promptFile = knowledgeFileStore.readFile(name);
            if (promptFile != null && promptFile.getContent() != null && !promptFile.getContent().trim().isEmpty()) {
                files.add(new PromptFileResponse(
                        name,
                        promptFile.getPath(),
                        true,
                        mode,
                        description));
            }
        }
    }

    private boolean hasReadableContent(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        try {
            return !new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim().isEmpty();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt file " + path, e);
        }
    }

    private List<SkillInfoResponse> skills(AgentContext context, Path configRoot, Path workspaceRoot) {
        List<SkillInfoResponse> skills = new ArrayList<SkillInfoResponse>();
        for (SkillDescriptor skill : skillRegistry.listSkills(context)) {
            skills.add(new SkillInfoResponse(
                    skill.getName(),
                    skill.getDescription(),
                    skill.getFilePath(),
                    skillScope(skill, configRoot, workspaceRoot)));
        }
        return skills;
    }

    private String skillScope(SkillDescriptor skill, Path configRoot, Path workspaceRoot) {
        Path filePath = parsePath(skill.getFilePath());
        if (filePath != null && !filePath.isAbsolute()) {
            return "database";
        }
        Path absoluteFilePath = filePath == null ? null : filePath.toAbsolutePath().normalize();
        if (absoluteFilePath != null && workspaceRoot != null && absoluteFilePath.startsWith(workspaceRoot.toAbsolutePath().normalize())) {
            return "project";
        }
        if (absoluteFilePath != null && configRoot != null && absoluteFilePath.startsWith(configRoot.toAbsolutePath().normalize())) {
            return "global";
        }
        return "provider";
    }

    private Path parsePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        try {
            return Paths.get(path.trim()).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

}

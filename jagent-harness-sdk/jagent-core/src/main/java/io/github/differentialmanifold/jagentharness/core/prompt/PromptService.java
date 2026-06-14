package io.github.differentialmanifold.jagentharness.core.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;

public class PromptService implements PromptProvider {

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a server-side agent running inside JAgentHarness.\n"
                    + "Use available tools and skills to help the user accomplish the task.\n"
                    + "Every executable capability is exposed as a registered tool, and every available skill describes a reusable workflow or domain instruction.\n"
                    + "When a tool result is needed, call the tool and continue after the result is returned.\n";

    private final SkillRegistry skillRegistry;
    private final Path defaultConfigRoot;
    private final KnowledgeFileStore knowledgeFileStore;
    private final PromptBindingStore promptBindingStore;

    public PromptService(SkillRegistry skillRegistry) {
        this(skillRegistry, Paths.get("."));
    }

    public PromptService(SkillRegistry skillRegistry, Path defaultConfigRoot) {
        this(skillRegistry, defaultConfigRoot, null, null);
    }

    public PromptService(SkillRegistry skillRegistry,
                         Path defaultConfigRoot,
                         KnowledgeFileStore knowledgeFileStore,
                         PromptBindingStore promptBindingStore) {
        this.skillRegistry = skillRegistry;
        this.defaultConfigRoot = defaultConfigRoot == null
                ? Paths.get(".").toAbsolutePath().normalize()
                : defaultConfigRoot.toAbsolutePath().normalize();
        this.knowledgeFileStore = knowledgeFileStore;
        this.promptBindingStore = promptBindingStore;
    }

    public String buildSystemPrompt(Collection<ToolDefinition> tools) {
        return buildSystemPrompt(tools, configRoot());
    }

    public String buildSystemPrompt(Collection<ToolDefinition> tools, Path configRoot) {
        return buildSystemPrompt(new PromptContext(
                tools,
                new AgentContext(null, null, null, null, configRoot, null)));
    }

    @Override
    public String buildSystemPrompt(PromptContext context) {
        PromptContext effectiveContext = context == null
                ? new PromptContext(null, new AgentContext(null, null, null, null, configRoot(), null))
                : context;
        AgentContext agentContext = effectiveContext.getAgentContext();
        Path root = configRoot(agentContext);
        Path workspaceRoot = workspaceRoot(agentContext);
        StringBuilder prompt = new StringBuilder();
        appendDefaultSystemPrompt(prompt);
        appendAgentRules(prompt, root, workspaceRoot);

        prompt.append("Available tools:\n");
        for (ToolDefinition tool : effectiveContext.getTools()) {
            prompt.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }
        prompt.append("\n");

        List<SkillDescriptor> skills = skillRegistry.listSkills(agentContext);
        if (!skills.isEmpty()) {
            boolean hasFileSkill = false;
            prompt.append("Available skills:\n");
            for (SkillDescriptor skill : skills) {
                prompt.append("- ").append(skill.getName())
                        .append(": ").append(skill.getDescription());
                if (!isBlank(skill.getFilePath())) {
                    hasFileSkill = true;
                    prompt.append(" (file: ").append(skill.getFilePath()).append(")");
                }
                prompt.append("\n");
            }
            if (hasFileSkill) {
                prompt.append("When a skill is relevant, call the skill tool with its SKILL.md path before following it. ");
                prompt.append("Every path under skills/ is a skill resource: always load it with the skill tool, never with the read tool. ");
                prompt.append("If SKILL.md references a relative path, resolve it relative to the directory containing SKILL.md and call the skill tool with the full resolved path.\n\n");
            } else {
                prompt.append("\n");
            }
        }

        return prompt.toString().trim();
    }

    private void appendDefaultSystemPrompt(StringBuilder prompt) {
        prompt.append(DEFAULT_SYSTEM_PROMPT).append("\n");
    }

    private void appendAgentRules(StringBuilder prompt, Path configRoot, Path workspaceRoot) {
        List<String> contents = agentRuleFiles(configRoot, workspaceRoot);
        if (contents.isEmpty()) {
            return;
        }

        prompt.append("## Agent Rules\n");
        for (int i = 0; i < contents.size(); i++) {
            if (i > 0) {
                prompt.append("\n\n");
            }
            prompt.append(contents.get(i).trim());
        }
        prompt.append("\n\n");
    }

    private List<String> agentRuleFiles(Path configRoot, Path workspaceRoot) {
        List<String> files = new ArrayList<String>();
        addAgentRuleFile(files, readIfExists(configRoot == null ? null : configRoot.resolve("AGENTS.md")));
        addAgentRuleFile(files, readIfExists(workspaceRoot == null ? null : workspaceRoot.resolve("AGENTS.md")));
        for (String content : readKnowledgePromptFiles("AGENTS.md")) {
            addAgentRuleFile(files, content);
        }
        return files;
    }

    private void addAgentRuleFile(List<String> files, String content) {
        if (!content.isEmpty()) {
            files.add(content);
        }
    }

    private List<String> readKnowledgePromptFiles(String promptName) {
        List<String> contents = new ArrayList<String>();
        if (knowledgeFileStore == null) {
            return contents;
        }
        boolean readBinding = false;
        if (promptBindingStore != null) {
            for (PromptBinding binding : promptBindingStore.listBindings(promptName)) {
                if (binding == null || isBlank(binding.getFilePath())) {
                    continue;
                }
                String content = readKnowledgeIfExists(binding.getFilePath());
                if (!content.isEmpty()) {
                    contents.add(content);
                    readBinding = true;
                }
            }
        }
        if (!readBinding) {
            String content = readKnowledgeIfExists(promptName);
            if (!content.isEmpty()) {
                contents.add(content);
            }
        }
        return contents;
    }

    private String readKnowledgeIfExists(String path) {
        KnowledgeFile file = knowledgeFileStore.readFile(path);
        if (file == null) {
            return "";
        }
        return file.getContent() == null ? "" : file.getContent();
    }

    private String readIfExists(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt file " + path, e);
        }
    }

    private Path configRoot() {
        return defaultConfigRoot;
    }

    private Path configRoot(AgentContext context) {
        if (context != null && context.getConfigRoot() != null) {
            return context.getConfigRoot().toAbsolutePath().normalize();
        }
        return configRoot();
    }

    private Path workspaceRoot(AgentContext context) {
        return context == null || context.getWorkspaceRoot() == null
                ? null
                : context.getWorkspaceRoot().toAbsolutePath().normalize();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

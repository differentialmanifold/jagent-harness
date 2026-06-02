package io.github.differentialmanifold.jagentharness.core.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;

public class PromptService implements PromptProvider {

    private final SkillRegistry skillRegistry;
    private final Path defaultConfigRoot;

    public PromptService(SkillRegistry skillRegistry) {
        this(skillRegistry, Paths.get("."));
    }

    public PromptService(SkillRegistry skillRegistry, Path defaultConfigRoot) {
        this.skillRegistry = skillRegistry;
        this.defaultConfigRoot = defaultConfigRoot == null
                ? Paths.get(".").toAbsolutePath().normalize()
                : defaultConfigRoot.toAbsolutePath().normalize();
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
        String customSystem = readIfExists(root.resolve("SYSTEM.md"));
        StringBuilder prompt = new StringBuilder();
        if (!customSystem.isEmpty()) {
            prompt.append(customSystem.trim()).append("\n\n");
        } else {
            prompt.append("You are a server-side agent running inside JAgentHarness.\n");
            prompt.append("Use tools only by returning OpenAI-compatible function tool calls. ");
            prompt.append("Every capability is exposed as a registered Java method tool.\n");
            prompt.append("When a tool result is needed, call the tool and continue after the result is returned.\n\n");
        }

        appendContextFile(prompt, root.resolve("AGENTS.md"), "Agent Rules");

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
                if (!isBlank(skill.getDirectoryPath())) {
                    hasFileSkill = true;
                    prompt.append(" (file: ").append(skill.getFilePath())
                            .append("; directory: ").append(skill.getDirectoryPath()).append(")");
                } else if (!isBlank(skill.getFilePath())) {
                    prompt.append(" (source: ").append(skill.getFilePath()).append(")");
                }
                prompt.append("\n");
            }
            if (hasFileSkill) {
                prompt.append("When a file skill is relevant, call read with its SKILL.md file path before following it. ");
                prompt.append("If that SKILL.md references a relative path, resolve it relative to the skill directory shown above and read the full resolved path.\n\n");
            } else {
                prompt.append("\n");
            }
        }

        return prompt.toString().trim();
    }

    private void appendContextFile(StringBuilder prompt, Path path, String title) {
        String content = readIfExists(path);
        if (!content.isEmpty()) {
            prompt.append("## ").append(title).append("\n");
            prompt.append(content.trim()).append("\n\n");
        }
    }

    private String readIfExists(Path path) {
        if (!Files.isRegularFile(path)) {
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

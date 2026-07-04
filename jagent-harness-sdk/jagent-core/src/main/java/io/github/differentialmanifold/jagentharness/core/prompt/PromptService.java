package io.github.differentialmanifold.jagentharness.core.prompt;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
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
    private final List<SystemPromptContributor> systemPromptContributors;

    public PromptService(SkillRegistry skillRegistry) {
        this(skillRegistry, Paths.get("."));
    }

    public PromptService(SkillRegistry skillRegistry, Path defaultConfigRoot) {
        this(skillRegistry, defaultConfigRoot, null, Collections.emptyList());
    }

    public PromptService(SkillRegistry skillRegistry,
                         Path defaultConfigRoot,
                         KnowledgeFileStore knowledgeFileStore) {
        this(skillRegistry, defaultConfigRoot, knowledgeFileStore, Collections.emptyList());
    }

    public PromptService(SkillRegistry skillRegistry,
                         Path defaultConfigRoot,
                         KnowledgeFileStore knowledgeFileStore,
                         List<SystemPromptContributor> systemPromptContributors) {
        this.skillRegistry = skillRegistry;
        this.defaultConfigRoot = defaultConfigRoot == null
                ? Paths.get(".").toAbsolutePath().normalize()
                : defaultConfigRoot.toAbsolutePath().normalize();
        this.knowledgeFileStore = knowledgeFileStore;
        this.systemPromptContributors = systemPromptContributors == null
                ? Collections.emptyList()
                : new ArrayList<SystemPromptContributor>(systemPromptContributors);
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
        StringBuilder prompt = new StringBuilder();
        appendDefaultSystemPrompt(prompt);
        appendSystemPromptContributors(prompt, effectiveContext);
        appendAgentRules(prompt, agentContext == null ? null : agentContext.getProjectId());

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

    private void appendSystemPromptContributors(StringBuilder prompt, PromptContext context) {
        List<String> contents = new ArrayList<String>();
        for (SystemPromptContributor contributor : systemPromptContributors) {
            if (contributor == null) {
                continue;
            }
            String content = contributor.contribute(context);
            if (!isBlank(content)) {
                contents.add(content.trim());
            }
        }
        if (contents.isEmpty()) {
            return;
        }

        prompt.append("## Application System Instructions\n");
        for (int i = 0; i < contents.size(); i++) {
            if (i > 0) {
                prompt.append("\n\n");
            }
            prompt.append(contents.get(i));
        }
        prompt.append("\n\n");
    }

    private void appendAgentRules(StringBuilder prompt, String projectId) {
        List<String> contents = agentRuleFiles(projectId);
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

    private List<String> agentRuleFiles(String projectId) {
        List<String> files = new ArrayList<String>();
        addAgentRuleFile(files, readKnowledgeIfExists(KnowledgeScope.global(), "AGENTS.md"));
        if (!isBlank(projectId)) {
            addAgentRuleFile(files, readKnowledgeIfExists(KnowledgeScope.project(projectId), "AGENTS.md"));
        }
        return files;
    }

    private void addAgentRuleFile(List<String> files, String content) {
        if (!content.isEmpty()) {
            files.add(content);
        }
    }

    private String readKnowledgeIfExists(KnowledgeScope scope, String path) {
        if (knowledgeFileStore == null) {
            return "";
        }
        KnowledgeFile file = knowledgeFileStore.readFile(scope, path);
        if (file == null) {
            return "";
        }
        return file.getContent() == null ? "" : file.getContent();
    }

    private Path configRoot() {
        return defaultConfigRoot;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

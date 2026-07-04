package io.github.differentialmanifold.jagentharness.core.prompt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.fs.TestKnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class PromptServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void tellsModelHowToLoadFileSkills() {
        SkillDescriptor skill = new SkillDescriptor(
                "java-review",
                "Use when reviewing Java code.",
                "/tmp/skills/java-review/SKILL.md");
        PromptService promptService = new PromptService(new SkillRegistry(Collections.singletonList(
                context -> Collections.singletonList(skill))));

        String prompt = promptService.buildSystemPrompt(Collections.emptyList());

        assertTrue(prompt.contains("file: /tmp/skills/java-review/SKILL.md"));
        assertFalse(prompt.contains("directory: /tmp/skills/java-review"));
        assertTrue(prompt.contains("call the skill tool with its SKILL.md path"));
        assertTrue(prompt.contains("always load it with the skill tool, never with the read tool"));
        assertTrue(prompt.contains("call the skill tool with the full resolved path"));
    }

    @Test
    void ignoresSystemPromptFilesAndUsesBuiltInSystemPrompt() throws IOException {
        Path globalRoot = tempDir.resolve("global");
        Path workspaceRoot = tempDir.resolve("workspace");
        write(globalRoot.resolve("SYSTEM.md"), "Global system override.");
        write(workspaceRoot.resolve("SYSTEM.md"), "Project system override.");
        TestKnowledgeFileStore store = new TestKnowledgeFileStore();
        store.writeFile("SYSTEM.md", "Database system override.\n", "text/markdown");
        store.writeFile(KnowledgeScope.project("project-1"), "SYSTEM.md", "Project database override.\n", "text/markdown");
        PromptService promptService = new PromptService(
                new SkillRegistry(Collections.emptyList()),
                globalRoot,
                store);

        String prompt = promptService.buildSystemPrompt(new PromptContext(
                Collections.emptyList(),
                new AgentContext("session", "turn", null, workspaceRoot, globalRoot, null, "project-1")));

        assertTrue(prompt.contains("You are a server-side agent running inside JAgentHarness."));
        assertTrue(prompt.contains("Use available tools and skills to help the user accomplish the task."));
        assertTrue(prompt.contains("Every executable capability is exposed as a registered tool"));
        assertFalse(prompt.contains("OpenAI-compatible"));
        assertFalse(prompt.contains("Java method"));
        assertFalse(prompt.contains("Global system override."));
        assertFalse(prompt.contains("Project system override."));
        assertFalse(prompt.contains("Database system override."));
        assertFalse(prompt.contains("Project database override."));
    }

    @Test
    void appendsSystemPromptContributorsBeforeAgentRules() throws IOException {
        Path globalRoot = tempDir.resolve("global");
        Path workspaceRoot = tempDir.resolve("workspace");
        TestKnowledgeFileStore store = new TestKnowledgeFileStore();
        store.writeFile(KnowledgeScope.project("project-1"), "AGENTS.md", "Use user project rules.", "text/markdown");
        PromptService promptService = new PromptService(
                new SkillRegistry(Collections.emptyList()),
                globalRoot,
                store,
                Collections.singletonList(context -> "Use application-specific tool rules."));

        String prompt = promptService.buildSystemPrompt(new PromptContext(
                Collections.emptyList(),
                new AgentContext("session", "turn", null, workspaceRoot, globalRoot, null, "project-1")));

        assertInOrder(prompt,
                "You are a server-side agent running inside JAgentHarness.",
                "## Application System Instructions",
                "Use application-specific tool rules.",
                "## Agent Rules",
                "Use user project rules.");
    }

    @Test
    void appendsAllAgentRuleFilesWithoutOverriding() throws IOException {
        Path globalRoot = tempDir.resolve("global");
        Path workspaceRoot = tempDir.resolve("workspace");
        TestKnowledgeFileStore store = new TestKnowledgeFileStore();
        store.writeFile(KnowledgeScope.global(), "AGENTS.md", "Use global rules.\n", "text/markdown");
        store.writeFile(KnowledgeScope.project("project-1"), "AGENTS.md", "Use project rules.\n", "text/markdown");
        PromptService promptService = new PromptService(
                new SkillRegistry(Collections.emptyList()),
                globalRoot,
                store);

        String prompt = promptService.buildSystemPrompt(new PromptContext(
                Collections.emptyList(),
                new AgentContext("session", "turn", null, workspaceRoot, globalRoot, null, "project-1")));

        assertInOrder(prompt,
                "## Agent Rules",
                "Use global rules.",
                "Use project rules.");
        assertFalse(prompt.contains("## Global Agent Rules"));
        assertFalse(prompt.contains("## Project Agent Rules"));
    }

    private void assertInOrder(String text, String... fragments) {
        int index = -1;
        for (String fragment : fragments) {
            int next = text.indexOf(fragment, index + 1);
            assertTrue(next > index, "Expected to find '" + fragment + "' after index " + index);
            index = next;
        }
    }

    private void write(Path path, String... lines) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, Arrays.asList(lines), StandardCharsets.UTF_8);
    }
}

package io.github.differentialmanifold.jagentharness.core.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void databaseSkillOverridesProjectAndGlobalSkillsWithSameName() {
        Path globalRoot = tempDir.resolve("global");
        Path workspaceRoot = tempDir.resolve("workspace");
        SkillDescriptor database = new SkillDescriptor(
                "review",
                "Database review workflow.",
                "skills/review/SKILL.md");
        SkillDescriptor global = new SkillDescriptor(
                "review",
                "Global review workflow.",
                globalRoot.resolve("skills/review/SKILL.md").toString());
        SkillDescriptor project = new SkillDescriptor(
                "review",
                "Project review workflow.",
                workspaceRoot.resolve("skills/review/SKILL.md").toString());
        SkillRegistry registry = new SkillRegistry(Arrays.asList(
                context -> Collections.singletonList(database),
                context -> Arrays.asList(global, project)));

        List<SkillDescriptor> skills = registry.listSkills(context(globalRoot, workspaceRoot));

        assertEquals(1, skills.size());
        assertEquals("Database review workflow.", skills.get(0).getDescription());
        assertEquals("skills/review/SKILL.md", skills.get(0).getFilePath());
    }

    @Test
    void projectSkillOverridesGlobalSkillWithSameName() {
        Path globalRoot = tempDir.resolve("global");
        Path workspaceRoot = tempDir.resolve("workspace");
        SkillDescriptor project = new SkillDescriptor(
                "review",
                "Project review workflow.",
                workspaceRoot.resolve("skills/review/SKILL.md").toString());
        SkillDescriptor global = new SkillDescriptor(
                "review",
                "Global review workflow.",
                globalRoot.resolve("skills/review/SKILL.md").toString());
        SkillRegistry registry = new SkillRegistry(Arrays.asList(
                context -> Collections.singletonList(project),
                context -> Collections.singletonList(global)));

        List<SkillDescriptor> skills = registry.listSkills(context(globalRoot, workspaceRoot));

        assertEquals(1, skills.size());
        assertEquals("Project review workflow.", skills.get(0).getDescription());
        assertEquals(project.getFilePath(), skills.get(0).getFilePath());
    }

    private AgentContext context(Path globalRoot, Path workspaceRoot) {
        return new AgentContext("session", "turn", null, workspaceRoot, globalRoot, null);
    }
}

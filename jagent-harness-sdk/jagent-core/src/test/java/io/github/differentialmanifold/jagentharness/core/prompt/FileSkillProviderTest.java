package io.github.differentialmanifold.jagentharness.core.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSkillProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsStandardSkillFrontmatter() throws IOException {
        Path skill = tempDir.resolve("skills/code-review/SKILL.md");
        write(skill,
                "---",
                "name: code-review",
                "description: Use when reviewing Java code changes.",
                "metadata:",
                "  short-description: Review code",
                "---",
                "",
                "# Code Review",
                "",
                "Full workflow.");

        List<SkillDescriptor> skills = new FileSkillProvider(tempDir, "skills").listSkills();

        assertEquals(1, skills.size());
        assertEquals("code-review", skills.get(0).getName());
        assertEquals("Use when reviewing Java code changes.", skills.get(0).getDescription());
        assertEquals(skill.toAbsolutePath().normalize().toString(), skills.get(0).getFilePath());
        assertEquals(skill.getParent().toAbsolutePath().normalize().toString(), skills.get(0).getDirectoryPath());
    }

    @Test
    void fallsBackToMarkdownTitleAndFirstParagraph() throws IOException {
        Path skill = tempDir.resolve("skills/simple/SKILL.md");
        write(skill,
                "# Simple Skill",
                "",
                "Use when a simple markdown skill is enough.",
                "",
                "Detailed workflow.");

        List<SkillDescriptor> skills = new FileSkillProvider(tempDir, "skills").listSkills();

        assertEquals(1, skills.size());
        assertEquals("Simple Skill", skills.get(0).getName());
        assertEquals("Use when a simple markdown skill is enough.", skills.get(0).getDescription());
    }

    @Test
    void workspaceSkillOverridesGlobalSkillWithSameName() throws IOException {
        Path globalRoot = tempDir.resolve("global");
        Path workspaceRoot = tempDir.resolve("workspace");
        Path globalSkill = globalRoot.resolve("skills/review/SKILL.md");
        Path workspaceSkill = workspaceRoot.resolve("skills/review/SKILL.md");
        write(globalSkill,
                "---",
                "name: review",
                "description: Global review workflow.",
                "---",
                "",
                "# Review");
        write(workspaceSkill,
                "---",
                "name: review",
                "description: Workspace review workflow.",
                "---",
                "",
                "# Review");

        FileSkillProvider provider = new FileSkillProvider(globalRoot, "skills");
        List<SkillDescriptor> skills = provider.listSkills(new AgentContext(
                "session",
                "turn",
                null,
                workspaceRoot,
                globalRoot,
                null));

        assertEquals(1, skills.size());
        assertEquals("review", skills.get(0).getName());
        assertEquals("Workspace review workflow.", skills.get(0).getDescription());
        assertEquals(workspaceSkill.toAbsolutePath().normalize().toString(), skills.get(0).getFilePath());
    }

    private void write(Path path, String... lines) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, Arrays.asList(lines), StandardCharsets.UTF_8);
    }
}

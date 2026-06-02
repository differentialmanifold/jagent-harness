package io.github.differentialmanifold.jagentharness.core.prompt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

class PromptServiceTest {

    @Test
    void tellsModelHowToReadFileSkills() {
        SkillDescriptor skill = new SkillDescriptor(
                "java-review",
                "Use when reviewing Java code.",
                "/tmp/skills/java-review/SKILL.md",
                "/tmp/skills/java-review");
        PromptService promptService = new PromptService(new SkillRegistry(Collections.singletonList(
                context -> Collections.singletonList(skill))));

        String prompt = promptService.buildSystemPrompt(Collections.emptyList());

        assertTrue(prompt.contains("file: /tmp/skills/java-review/SKILL.md; directory: /tmp/skills/java-review"));
        assertTrue(prompt.contains("call read with its SKILL.md file path"));
        assertTrue(prompt.contains("resolve it relative to the skill directory"));
    }
}

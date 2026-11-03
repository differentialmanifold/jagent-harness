package io.github.differentialmanifold.jagentharness.core.skill;

import static org.junit.jupiter.api.Assertions.*;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.prompt.*;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillActivationTest {
    @Test
    void parsesExactDependenciesAndDeduplicatesWithoutRequiringThemForOldSkills() {
        assertTrue(SkillFileParser.readToolDependencies("# Old skill").isEmpty());
        List<SkillToolDependency> parsed =
                SkillFileParser.readToolDependencies(
                        "---\ntool_dependencies:\n  - server: shop\n    tools: [get, get]\n  - server: shop\n    tools: [refund]\n---\n");
        assertEquals(1, parsed.size());
        assertEquals(Arrays.asList("get", "refund"), parsed.get(0).getTools());
        for (String invalid :
                Arrays.asList(
                        "null",
                        "{}",
                        "[{}]",
                        "[{server: shop, tools: []}]",
                        "[{server: shop, tools: '*'}]",
                        "[{server: shop, tools: ['*']}]",
                        "[{server: shop, tools: [42]}]",
                        "[{server: shop, tools: [get], optional: true}]")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            SkillFileParser.readToolDependencies(
                                    "---\ntool_dependencies: " + invalid + "\n---\n"),
                    invalid);
        }
    }

    @Test
    void activationReceiptsSurviveMissingConversationAndDoNotClaimInstructionsAreLoaded() {
        InMemorySkillActivationStore store = new InMemorySkillActivationStore();
        SkillActivationService service = new SkillActivationService(store, () -> null);
        AgentContext context = new AgentContext("session", "run", "turn");
        service.activate(context, "skills/refund/SKILL.md", "global:", "# Instructions");
        service.activate(context, "skills/refund/SKILL.md", "global:", "# Instructions");
        assertEquals(1, store.find("session", "global").size());
        PromptService prompts =
                new PromptService(
                        new SkillRegistry(Collections.emptyList()),
                        Paths.get("."),
                        null,
                        Collections.emptyList(),
                        store);
        String prompt =
                prompts.buildSystemPrompt(new PromptContext(Collections.emptyList(), context));
        assertTrue(prompt.contains("skills/refund/SKILL.md"));
        assertTrue(prompt.contains(SkillActivation.digest("# Instructions")));
        assertTrue(prompt.contains("not instructions"));
        assertTrue(prompt.contains("reload its SKILL.md"));
        assertFalse(prompt.contains("# Instructions"));
        String fresh =
                prompts.buildSystemPrompt(
                        new PromptContext(
                                Collections.emptyList(),
                                new AgentContext("new-session", "run", "turn")));
        assertFalse(fresh.contains("skills/refund/SKILL.md"));
    }
}

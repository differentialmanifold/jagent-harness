package io.github.differentialmanifold.jagentharness.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void laterRegisteredToolOverridesEarlierToolWithSameName() {
        ToolRegistry registry = new ToolRegistry();
        ToolDefinition original = new StubTool("read", "original");
        ToolDefinition replacement = new StubTool("read", "replacement");

        registry.register(original);
        registry.register(replacement);

        assertSame(replacement, registry.get("read"));
        assertEquals(1, registry.all().size());
    }

    @Test
    void overriddenToolMovesToLatestRegistrationPosition() {
        ToolRegistry registry = new ToolRegistry();
        ToolDefinition read = new StubTool("read", "original");
        ToolDefinition bash = new StubTool("bash", "bash");
        ToolDefinition replacement = new StubTool("read", "replacement");

        registry.register(read);
        registry.register(bash);
        registry.register(replacement);

        List<ToolDefinition> tools = new ArrayList<ToolDefinition>(registry.all());
        assertSame(bash, tools.get(0));
        assertSame(replacement, tools.get(1));
    }

    private static class StubTool implements ToolDefinition {

        private final String name;
        private final String description;

        private StubTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public JsonNode getParametersSchema() {
            return null;
        }

        @Override
        public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
            return ToolExecutionResult.of(description);
        }
    }
}

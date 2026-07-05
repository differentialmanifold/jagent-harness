package io.github.differentialmanifold.jagentharness.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
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

    @Test
    void resolvesDynamicToolsForCurrentContext() {
        ToolDefinition dynamic = new StubTool("remote", "remote");
        ToolProvider provider = context -> context == null || context.getSessionId() == null
                ? Collections.<ToolDefinition>emptyList()
                : Collections.singletonList(dynamic);
        ToolRegistry registry = new ToolRegistry(
                Collections.singletonList(new StubTool("local", "local")),
                Collections.singletonList(provider));

        assertEquals(1, registry.all().size());
        List<ToolDefinition> tools = new ArrayList<ToolDefinition>(
                registry.all(new AgentContext("session", "turn")));
        assertEquals(2, tools.size());
        assertSame(dynamic, tools.get(1));
    }

    @Test
    void getsDynamicToolForCurrentContext() {
        ToolDefinition dynamic = new StubTool("remote", "remote");
        ToolProvider provider = context -> context != null && "session".equals(context.getSessionId())
                ? Collections.singletonList(dynamic)
                : Collections.<ToolDefinition>emptyList();
        ToolRegistry registry = new ToolRegistry(
                Collections.<ToolDefinition>emptyList(),
                Collections.singletonList(provider));

        assertSame(dynamic, registry.get("remote", new AgentContext("session", "turn")));
        assertNull(registry.get("remote"));
        assertNull(registry.get("remote", new AgentContext("other", "turn")));
    }

    @Test
    void rejectsDynamicToolNameCollisions() {
        ToolRegistry registry = new ToolRegistry(
                Collections.singletonList(new StubTool("read", "local")),
                Collections.singletonList(context -> Collections.singletonList(new StubTool("read", "remote"))));

        assertThrows(IllegalStateException.class, () -> registry.all(new AgentContext("session", "turn")));
    }

    @Test
    void availabilityPoliciesFilterStaticToolsWithoutFilteringDynamicTools() {
        ToolDefinition dynamic = new StubTool("remote", "remote");
        ToolAvailabilityPolicy readOnly = (tools, context) -> {
            List<ToolDefinition> filtered = new ArrayList<ToolDefinition>();
            for (ToolDefinition tool : tools) {
                if ("read".equals(tool.getName())) {
                    filtered.add(tool);
                }
            }
            return filtered;
        };
        ToolRegistry registry = new ToolRegistry(
                java.util.Arrays.<ToolDefinition>asList(
                        new StubTool("read", "read"),
                        new StubTool("bash", "bash")),
                Collections.singletonList(context -> Collections.singletonList(dynamic)),
                Collections.singletonList(readOnly));

        List<ToolDefinition> resolved = new ArrayList<ToolDefinition>(
                registry.all(new AgentContext("session", "turn")));

        assertEquals(2, resolved.size());
        assertEquals("read", resolved.get(0).getName());
        assertSame(dynamic, resolved.get(1));
        assertEquals(2, registry.registeredTools().size());
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

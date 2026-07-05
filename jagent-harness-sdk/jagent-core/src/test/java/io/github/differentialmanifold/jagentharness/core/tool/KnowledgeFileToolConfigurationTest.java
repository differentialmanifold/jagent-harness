package io.github.differentialmanifold.jagentharness.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.core.fs.TestKnowledgeFileStore;
import org.junit.jupiter.api.Test;

class KnowledgeFileToolConfigurationTest {

    private final TestKnowledgeFileStore fileStore = new TestKnowledgeFileStore();
    private final KnowledgeFileToolConfiguration configuration =
            new KnowledgeFileToolConfiguration(fileStore, new ObjectMapper());

    @Test
    void enablesEveryToolWhenConfigurationDoesNotExist() {
        ToolSelectionSnapshot snapshot = configuration.load();

        assertFalse(snapshot.isConfigured());
        assertTrue(snapshot.isEnabled("read"));
    }

    @Test
    void savesAndAppliesExplicitEnabledTools() {
        configuration.save(Arrays.asList("read", "grep", "read"));

        ToolSelectionSnapshot snapshot = configuration.load();
        Collection<ToolDefinition> filtered = configuration.filter(
                Arrays.<ToolDefinition>asList(new StubTool("read"), new StubTool("bash")),
                null);

        assertTrue(snapshot.isConfigured());
        assertEquals(new LinkedHashSet<String>(Arrays.asList("read", "grep")), snapshot.getEnabledTools());
        assertEquals(1, filtered.size());
        assertEquals("read", filtered.iterator().next().getName());
    }

    @Test
    void deleteRestoresDefaultSelection() {
        configuration.save(Collections.singletonList("read"));

        configuration.delete();

        assertFalse(configuration.load().isConfigured());
        assertTrue(configuration.load().isEnabled("bash"));
    }

    @Test
    void rejectsMalformedConfiguration() {
        fileStore.writeFile(
                KnowledgeScope.global(),
                KnowledgeFileToolConfiguration.CONFIG_PATH,
                "{\"enabledTools\":\"read\"}",
                "application/json");

        assertThrows(IllegalStateException.class, configuration::load);
    }

    private static class StubTool implements ToolDefinition {

        private final String name;

        private StubTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return name;
        }

        @Override
        public JsonNode getParametersSchema() {
            return null;
        }

        @Override
        public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
            return ToolExecutionResult.of(name);
        }
    }
}

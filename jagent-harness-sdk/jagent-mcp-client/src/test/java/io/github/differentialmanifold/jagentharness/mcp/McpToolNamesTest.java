package io.github.differentialmanifold.jagentharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class McpToolNamesTest {

    @Test
    void prefixesSanitizesAndBoundsModelToolNames() {
        assertEquals("catalog__find_items", McpToolNames.modelName("catalog", "find items"));

        String longName = McpToolNames.modelName(
                "very-long-server-name",
                "a-tool-name-that-is-longer-than-the-model-provider-allows-and-needs-a-hash");
        assertEquals(64, longName.length());
        assertTrue(longName.matches("[A-Za-z0-9_-]+"));
        assertEquals(longName, McpToolNames.modelName(
                "very-long-server-name",
                "a-tool-name-that-is-longer-than-the-model-provider-allows-and-needs-a-hash"));
    }
}

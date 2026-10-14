package io.github.differentialmanifold.jagentharness.tools.coding;

import static org.junit.jupiter.api.Assertions.*;

import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingToolsTest {
    @TempDir Path directory;

    @Test
    void codingWorkspaceIsInjectedIntoToolsAndResultsAreStructured() throws Exception {
        java.nio.file.Files.write(
                directory.resolve("example.txt"),
                "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ToolDefinition read =
                new CodingTools()
                        .tools().stream()
                                .filter(tool -> "read".equals(tool.getName()))
                                .findFirst()
                                .get();
        io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult result =
                read.execute(
                        new io.github.differentialmanifold.jagentharness.core.tool.ToolContext(
                                "s", "r", "t", null, directory, null, Collections.emptyMap()),
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .createObjectNode()
                                .put("path", "example.txt"));
        assertEquals("SUCCEEDED", result.status);
        assertTrue(result.content instanceof com.fasterxml.jackson.databind.JsonNode);
        assertEquals(
                "hello",
                ((com.fasterxml.jackson.databind.JsonNode) result.content)
                        .path("content")
                        .asText());
    }

    @Test
    void allToolsAreAvailableWithoutCommandLinePermissions() {
        Collection<? extends ToolDefinition> local = new CodingTools().tools();
        assertEquals(
                new HashSet<>(Arrays.asList("read", "ls", "find", "grep", "write", "edit", "bash")),
                names(local));
        for (ToolDefinition tool : local) {
            assertNotNull(tool.getParametersSchema());
            assertEquals("object", tool.getParametersSchema().path("type").asText());
        }
    }

    private Set<String> names(Collection<? extends ToolDefinition> local) {
        return local.stream().map(ToolDefinition::getName).collect(Collectors.toSet());
    }
}

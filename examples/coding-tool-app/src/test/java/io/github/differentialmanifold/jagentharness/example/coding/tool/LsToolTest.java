package io.github.differentialmanifold.jagentharness.example.coding.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LsToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void listsDirectoryUsingBackslashSeparators() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LsTool tool = new LsTool(objectMapper, new WorkspacePathResolver());
        Files.createDirectories(workspaceRoot.resolve("src/test"));
        Files.createFile(workspaceRoot.resolve("src/test/Example.java"));
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "src\\test");

        ToolExecutionResult executionResult = tool.execute(toolContext(), arguments);
        JsonNode result = objectMapper.readTree(executionResult.getContent());

        assertEquals("src/test", result.path("path").asText());
        assertEquals("src/test/Example.java", result.path("entries").get(0).path("path").asText());
    }

    @Test
    void reportsMissingDirectory() {
        ObjectMapper objectMapper = new ObjectMapper();
        LsTool tool = new LsTool(objectMapper, new WorkspacePathResolver());
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "missing\\directory");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments));

        assertEquals("Directory not found: missing/directory", error.getMessage());
    }

    private ToolContext toolContext() {
        return new ToolContext("session", "turn", workspaceRoot);
    }
}

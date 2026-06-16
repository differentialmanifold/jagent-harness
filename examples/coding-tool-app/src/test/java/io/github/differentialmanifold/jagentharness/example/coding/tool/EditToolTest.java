package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.nio.charset.StandardCharsets;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void returnsStructuredDiffForReplacement() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());

        Path file = workspaceRoot.resolve("App.vue");
        Files.write(file, ("line one\n"
                + "const oldValue = true\n"
                + "line three\n").getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "App.vue");
        arguments.put("search", "const oldValue = true");
        arguments.put("replacement", "const newValue = true\nconst otherValue = false");

        ToolExecutionResult executionResult = tool.execute(toolContext(), arguments);
        JsonNode result = objectMapper.readTree(executionResult.getContent());

        assertEquals("App.vue", result.path("path").asText());
        assertEquals("App.vue", result.path("fileName").asText());
        assertEquals(2, result.path("additions").asInt());
        assertEquals(1, result.path("deletions").asInt());
        assertEquals("removed", result.path("diff").path("hunks").get(0).path("lines").get(1).path("type").asText());
        assertEquals("added", result.path("diff").path("hunks").get(0).path("lines").get(2).path("type").asText());
        assertEquals("line one\n"
                + "const newValue = true\n"
                + "const otherValue = false\n"
                + "line three\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    void replacesLineRange() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());

        Path file = workspaceRoot.resolve("example.py");
        Files.write(file, ("one\n"
                + "two\n"
                + "three\n"
                + "four\n").getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "example.py");
        arguments.put("startLine", 2);
        arguments.put("endLine", 3);
        arguments.put("replacement", "new two\nnew three");

        JsonNode result = objectMapper.readTree(tool.execute(toolContext(), arguments).getContent());

        assertEquals(2, result.path("additions").asInt());
        assertEquals(2, result.path("deletions").asInt());
        assertEquals("one\n"
                + "new two\n"
                + "new three\n"
                + "four\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    void insertsAfterLine() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());

        Path file = workspaceRoot.resolve("example.py");
        Files.write(file, ("one\n"
                + "two\n").getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "example.py");
        arguments.put("insertAfterLine", 1);
        arguments.put("replacement", "inserted");

        JsonNode result = objectMapper.readTree(tool.execute(toolContext(), arguments).getContent());

        assertEquals(1, result.path("additions").asInt());
        assertEquals(0, result.path("deletions").asInt());
        assertEquals("one\n"
                + "inserted\n"
                + "two\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    private ToolContext toolContext() {
        return new ToolContext("session", "turn", workspaceRoot);
    }
}

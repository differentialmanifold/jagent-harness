package io.github.differentialmanifold.jagentharness.example.coding.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

class GrepToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void searchesSingleFileUsingBackslashSeparators() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());
        write("src/test/java/com/paic/bst/Practice.java", "class Practice { // target text\n}\n");

        JsonNode result = execute(
                objectMapper,
                tool,
                arguments(objectMapper, "target text", "src\\test\\java\\com\\paic\\bst\\Practice.java", null));

        assertEquals("src/test/java/com/paic/bst/Practice.java", result.path("path").asText());
        assertEquals(1, result.path("matches").size());
        assertEquals(1, result.path("matches").get(0).path("line").asInt());
    }

    @Test
    void searchesSingleFileUsingForwardSlashSeparators() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());
        write("src/main/App.java", "final String value = \"needle\";\n");

        JsonNode result = execute(
                objectMapper,
                tool,
                arguments(objectMapper, "needle", "src/main/App.java", null));

        assertEquals(1, result.path("matches").size());
        assertEquals("src/main/App.java", result.path("matches").get(0).path("path").asText());
    }

    @Test
    void searchesDirectoryUsingBackslashGlob() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());
        write("src/main/App.java", "needle\n");
        write("src/main/ignored.txt", "needle\n");

        JsonNode result = execute(
                objectMapper,
                tool,
                arguments(objectMapper, "needle", ".", "src\\**\\*.java"));

        assertEquals(1, result.path("matches").size());
        assertEquals("src/main/App.java", result.path("matches").get(0).path("path").asText());
    }

    @Test
    void reportsMissingFileOrDirectoryAsMissingPath() {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(
                        toolContext(),
                        arguments(objectMapper, "needle", "missing\\Practice.java", null)));

        assertEquals("Path not found: missing/Practice.java", error.getMessage());
    }

    private JsonNode execute(ObjectMapper objectMapper, GrepTool tool, ObjectNode arguments) throws Exception {
        ToolExecutionResult result = tool.execute(toolContext(), arguments);
        return objectMapper.readTree(result.getContent());
    }

    private ObjectNode arguments(ObjectMapper objectMapper, String query, String path, String glob) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("query", query);
        arguments.put("path", path);
        if (glob != null) {
            arguments.put("glob", glob);
        }
        return arguments;
    }

    private ToolContext toolContext() {
        return new ToolContext("session", "run", "turn", workspaceRoot);
    }

    private void write(String relativePath, String content) throws Exception {
        Path path = workspaceRoot.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}

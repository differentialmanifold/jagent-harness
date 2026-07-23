package io.github.differentialmanifold.jagentharness.example.coding.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepBinaryResolver;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepExecutable;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepProcessRunner;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepSearchEngine;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void findsByNameAndSkipsGeneratedAndHiddenPathsByDefault() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        FindTool tool = new FindTool(objectMapper, new WorkspacePathResolver());
        write("src/main/App.java");
        write("src/test/AppTest.java");
        write("target/generated/App.java");
        write(".hidden/App.java");

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("name", "*.java");
        arguments.put("type", "file");

        JsonNode result = execute(objectMapper, tool, arguments);
        String content = result.path("matches").toString();

        assertTrue(content.contains("src/main/App.java"));
        assertTrue(content.contains("src/test/AppTest.java"));
        assertFalse(content.contains("target/generated/App.java"));
        assertFalse(content.contains(".hidden/App.java"));
        assertFalse(result.path("truncated").asBoolean());
        assertEquals("java", result.path("engine").asText());
    }

    @Test
    void limitsResultsAndReportsTruncation() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        FindTool tool = new FindTool(objectMapper, new WorkspacePathResolver());
        write("one.txt");
        write("two.txt");

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("name", "*.txt");
        arguments.put("type", "file");
        arguments.put("maxResults", 1);

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals(1, result.path("matches").size());
        assertTrue(result.path("truncated").asBoolean());
    }

    @Test
    void acceptsBackslashPathsGlobsAndExclusions() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        FindTool tool = new FindTool(objectMapper, new WorkspacePathResolver());
        write("src/main/app/App.java");
        write("src/main/generated/Generated.java");

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "src\\main");
        arguments.put("glob", "**\\*.java");
        arguments.put("type", "file");
        arguments.put("exclude", "generated\\**");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("src/main", result.path("path").asText());
        assertEquals(1, result.path("matches").size());
        assertEquals("src/main/app/App.java", result.path("matches").get(0).path("path").asText());
    }

    @Test
    void usesDetectedRipgrepForFileOnlySearches() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Optional<RipgrepExecutable> executable = new RipgrepBinaryResolver("").resolve();
        Assumptions.assumeTrue(executable.isPresent(), "ripgrep is not installed");
        FindTool tool = new FindTool(
                objectMapper,
                new WorkspacePathResolver(),
                new RipgrepSearchEngine(new RipgrepProcessRunner(), executable));
        write("src/App.java");
        write("src/App.txt");

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("glob", "**/*.java");
        arguments.put("type", "file");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("ripgrep", result.path("engine").asText());
        assertEquals(1, result.path("matches").size());
        assertEquals("src/App.java", result.path("matches").get(0).path("path").asText());
    }

    private JsonNode execute(ObjectMapper objectMapper, FindTool tool, ObjectNode arguments) throws Exception {
        ToolExecutionResult result = tool.execute(new ToolContext("session", "run", "turn", workspaceRoot), arguments);
        return objectMapper.readTree(result.getContent());
    }

    private void write(String path) throws Exception {
        Path file = workspaceRoot.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, "content\n".getBytes(StandardCharsets.UTF_8));
    }
}

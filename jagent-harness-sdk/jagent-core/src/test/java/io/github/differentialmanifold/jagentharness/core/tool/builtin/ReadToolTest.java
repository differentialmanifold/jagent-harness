package io.github.differentialmanifold.jagentharness.core.tool.builtin;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadToolTest {

    @TempDir
    Path workspaceRoot;

    @TempDir
    Path configRoot;

    @Test
    void readsTextFileUnderWorkspaceByRelativePath() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ReadTool tool = new ReadTool(objectMapper);

        Path file = workspaceRoot.resolve("example.py");
        Files.write(file, "print('hello')\n".getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "example.py");

        ToolExecutionResult executionResult = tool.execute(toolContext(), arguments);
        JsonNode result = objectMapper.readTree(executionResult.getContent());

        assertEquals("example.py", result.path("path").asText());
        assertEquals("text", result.path("type").asText());
        assertEquals("print('hello')\n", result.path("content").asText());
    }

    @Test
    void readsTextFileUnderConfigRootByAbsolutePath() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ReadTool tool = new ReadTool(objectMapper);

        Path skill = configRoot.resolve("skills/java-review/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.write(skill, "# Java Review\n".getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", skill.toString());

        ToolExecutionResult executionResult = tool.execute(toolContext(), arguments);
        JsonNode result = objectMapper.readTree(executionResult.getContent());

        assertEquals(skill.toAbsolutePath().normalize().toString(), result.path("path").asText());
        assertEquals("# Java Review\n", result.path("content").asText());
    }

    @Test
    void resolvesRelativePathUnderConfigRootWhenWorkspaceIsAbsent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ReadTool tool = new ReadTool(objectMapper);

        Path skill = configRoot.resolve("skills/java-review/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.write(skill, "# Java Review\n".getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "skills/java-review/SKILL.md");

        ToolExecutionResult executionResult = tool.execute(new ToolContext(
                "session",
                "turn",
                null,
                null,
                configRoot,
                null), arguments);
        JsonNode result = objectMapper.readTree(executionResult.getContent());

        assertEquals(skill.toAbsolutePath().normalize().toString(), result.path("path").asText());
        assertEquals("# Java Review\n", result.path("content").asText());
    }

    @Test
    void rejectsNonTextContent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ReadTool tool = new ReadTool(objectMapper);

        Path file = workspaceRoot.resolve("blob.bin");
        Files.write(file, new byte[] { 0, 1, 2, 3 });

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "blob.bin");

        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments));
    }

    @Test
    void rejectsAbsolutePathOutsideAllowedRoots() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ReadTool tool = new ReadTool(objectMapper);

        Path file = Files.createTempFile("outside", ".txt");
        Files.write(file, "outside\n".getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", file.toString());

        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments));
    }

    private ToolContext toolContext() {
        return new ToolContext(
                "session",
                "turn",
                null,
                workspaceRoot,
                configRoot,
                null);
    }
}

package io.github.differentialmanifold.jagentharness.example.coding.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.ContentHashing;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void readsWorkspaceFile() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ReadTool tool = new ReadTool(objectMapper, new WorkspacePathResolver());
        String content = "first\nsecond\nthird\n";
        write(workspaceRoot.resolve("example.txt"), content);

        JsonNode result = execute(objectMapper, tool, arguments(objectMapper, "example.txt", null, null));

        assertEquals("example.txt", result.path("path").asText());
        assertEquals("first\nsecond\nthird", result.path("content").asText());
        assertEquals(3, result.path("totalLines").asInt());
        assertFalse(result.path("truncated").asBoolean());
        assertEquals(
                ContentHashing.sha256(content.getBytes(StandardCharsets.UTF_8)),
                result.path("contentHash").asText());
    }

    @Test
    void hidesUtf8BomFromModelContentButHashesOriginalBytes() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ReadTool tool = new ReadTool(objectMapper, new WorkspacePathResolver());
        byte[] content = "first\r\nsecond\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[3 + content.length];
        bytes[0] = (byte) 0xef;
        bytes[1] = (byte) 0xbb;
        bytes[2] = (byte) 0xbf;
        System.arraycopy(content, 0, bytes, 3, content.length);
        Files.write(workspaceRoot.resolve("bom.txt"), bytes);

        JsonNode result = execute(objectMapper, tool, arguments(objectMapper, "bom.txt", null, null));

        assertEquals("first\nsecond", result.path("content").asText());
        assertEquals(ContentHashing.sha256(bytes), result.path("contentHash").asText());
    }

    @Test
    void readsWorkspaceFileUsingBackslashSeparators() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ReadTool tool = new ReadTool(objectMapper, new WorkspacePathResolver());
        write(workspaceRoot.resolve("src/test/Example.java"), "class Example {}\n");

        JsonNode result = execute(
                objectMapper,
                tool,
                arguments(objectMapper, "src\\test\\Example.java", null, null));

        assertEquals("src/test/Example.java", result.path("path").asText());
        assertEquals("class Example {}", result.path("content").asText());
    }

    @Test
    void readsLineRangeWithOffsetAndLimit() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ReadTool tool = new ReadTool(objectMapper, new WorkspacePathResolver());
        write(workspaceRoot.resolve("example.txt"), "first\nsecond\nthird\nfourth\n");

        JsonNode result = execute(objectMapper, tool, arguments(objectMapper, "example.txt", 2, 2));

        assertEquals("second\nthird", result.path("content").asText());
        assertEquals(2, result.path("offset").asInt());
        assertEquals(2, result.path("lines").asInt());
        assertEquals(4, result.path("totalLines").asInt());
        assertTrue(result.path("truncated").asBoolean());
        assertEquals(
                ContentHashing.sha256("first\nsecond\nthird\nfourth\n".getBytes(StandardCharsets.UTF_8)),
                result.path("contentHash").asText());
    }

    @Test
    void readsAbsolutePathOutsideWorkspace() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ReadTool tool = new ReadTool(objectMapper, new WorkspacePathResolver());
        Path outside = Files.createTempFile("outside", ".txt");
        Files.write(outside, "outside\ncontent\n".getBytes(StandardCharsets.UTF_8));

        JsonNode result = execute(objectMapper, tool, arguments(objectMapper, outside.toString(), null, null));

        assertEquals(
                outside.toAbsolutePath().normalize().toString().replace('\\', '/'),
                result.path("path").asText());
        assertEquals("outside\ncontent", result.path("content").asText());
    }

    @Test
    void validatesOffsetAndLimit() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ReadTool tool = new ReadTool(objectMapper, new WorkspacePathResolver());
        write(workspaceRoot.resolve("example.txt"), "content\n");

        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments(objectMapper, "example.txt", 0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments(objectMapper, "example.txt", 1, 2001)));
    }

    private JsonNode execute(ObjectMapper objectMapper, ReadTool tool, ObjectNode arguments) throws Exception {
        ToolExecutionResult result = tool.execute(toolContext(), arguments);
        return objectMapper.readTree(result.getContent());
    }

    private ObjectNode arguments(ObjectMapper objectMapper,
                                 String path,
                                 Integer offset,
                                 Integer limit) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", path);
        if (offset != null) {
            arguments.put("offset", offset);
        }
        if (limit != null) {
            arguments.put("limit", limit);
        }
        return arguments;
    }

    private ToolContext toolContext() {
        return new ToolContext("session", "run", "turn", null, workspaceRoot, null, null);
    }

    private void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}

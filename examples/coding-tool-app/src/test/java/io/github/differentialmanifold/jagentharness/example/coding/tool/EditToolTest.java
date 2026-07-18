package io.github.differentialmanifold.jagentharness.example.coding.tool;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void returnsStructuredDiffForReplacement() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());

        Path file = workspaceRoot.resolve("App.vue");
        String original = "line one\n"
                + "const oldValue = true\n"
                + "line three\n";
        Files.write(file, original.getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "App.vue");
        arguments.put("expectedHash", ContentHashing.sha256(original.getBytes(StandardCharsets.UTF_8)));
        arguments.put("search", "const oldValue = true");
        arguments.put("replacement", "const newValue = true\nconst otherValue = false");

        ToolExecutionResult executionResult = tool.execute(toolContext(), arguments);
        JsonNode result = objectMapper.readTree(executionResult.getContent());

        assertEquals("App.vue", result.path("path").asText());
        assertEquals("App.vue", result.path("fileName").asText());
        assertEquals(
                ContentHashing.sha256(original.getBytes(StandardCharsets.UTF_8)),
                result.path("previousHash").asText());
        assertEquals(2, result.path("additions").asInt());
        assertEquals(1, result.path("deletions").asInt());
        assertEquals("removed", result.path("diff").path("hunks").get(0).path("lines").get(1).path("type").asText());
        assertEquals("added", result.path("diff").path("hunks").get(0).path("lines").get(2).path("type").asText());
        assertEquals("line one\n"
                + "const newValue = true\n"
                + "const otherValue = false\n"
                + "line three\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(ContentHashing.sha256(Files.readAllBytes(file)), result.path("contentHash").asText());
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
    void replacesReadStyleTextInCrlfFile() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());

        Path file = workspaceRoot.resolve("windows.txt");
        Files.write(file, ("one\r\n"
                + "two\r\n"
                + "three\r\n").getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "windows.txt");
        arguments.put("search", "two\nthree");
        arguments.put("replacement", "new two\nnew three");

        JsonNode result = objectMapper.readTree(tool.execute(toolContext(), arguments).getContent());

        assertEquals(2, result.path("additions").asInt());
        assertEquals(2, result.path("deletions").asInt());
        assertEquals("two", result.path("diff").path("hunks").get(0).path("lines").get(1).path("content").asText());
        assertEquals("one\r\n"
                + "new two\r\n"
                + "new three\r\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    void preservesCrlfWhenReplacingLineRange() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());

        Path file = workspaceRoot.resolve("windows.txt");
        Files.write(file, ("one\r\n"
                + "two\r\n"
                + "three\r\n").getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "windows.txt");
        arguments.put("startLine", 2);
        arguments.put("endLine", 2);
        arguments.put("replacement", "dos");

        JsonNode result = objectMapper.readTree(tool.execute(toolContext(), arguments).getContent());

        assertEquals(1, result.path("additions").asInt());
        assertEquals(1, result.path("deletions").asInt());
        assertEquals("one\r\n"
                + "dos\r\n"
                + "three\r\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
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

    @Test
    void rejectsEditWhenFileChangedAfterRead() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = workspaceRoot.resolve("example.txt");
        byte[] original = "old value\n".getBytes(StandardCharsets.UTF_8);
        byte[] current = "changed value\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        String expectedHash = ContentHashing.sha256(original);
        Files.write(file, current);

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "example.txt");
        arguments.put("expectedHash", expectedHash);
        arguments.put("search", "changed value");
        arguments.put("replacement", "new value");

        JsonNode result = objectMapper.readTree(tool.execute(toolContext(), arguments).getContent());

        assertEquals("FILE_CHANGED", result.path("code").asText());
        assertEquals(expectedHash, result.path("expectedHash").asText());
        assertEquals(ContentHashing.sha256(current), result.path("contentHash").asText());
        assertEquals("changed value\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    void returnsCandidatesWhenExactSearchIsMissing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = workspaceRoot.resolve("PropertyEnum.java");
        String content = "public enum PropertyEnum {\n"
                + "    VALUE(\"actual\");\n"
                + "}\n";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "PropertyEnum.java");
        arguments.put("search", "VALUE(\"actual\", false);");
        arguments.put("replacement", "VALUE(\"updated\", false);");

        JsonNode result = objectMapper.readTree(tool.execute(toolContext(), arguments).getContent());

        assertEquals("SEARCH_NOT_FOUND", result.path("code").asText());
        assertEquals(ContentHashing.sha256(content.getBytes(StandardCharsets.UTF_8)),
                result.path("contentHash").asText());
        assertEquals(1, result.path("candidates").size());
        assertEquals(1, result.path("candidates").get(0).path("startLine").asInt());
        assertEquals(content, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsAmbiguousSearchUnlessOccurrenceIsSpecified() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = workspaceRoot.resolve("values.txt");
        Files.write(file, "value\nmiddle\nvalue\n".getBytes(StandardCharsets.UTF_8));

        ObjectNode ambiguousArguments = objectMapper.createObjectNode();
        ambiguousArguments.put("path", "values.txt");
        ambiguousArguments.put("search", "value");
        ambiguousArguments.put("replacement", "updated");

        JsonNode failure = objectMapper.readTree(tool.execute(toolContext(), ambiguousArguments).getContent());

        assertEquals("AMBIGUOUS_MATCH", failure.path("code").asText());
        assertEquals(2, failure.path("matchCount").asInt());
        assertEquals("value\nmiddle\nvalue\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));

        ObjectNode occurrenceArguments = ambiguousArguments.deepCopy();
        occurrenceArguments.put("occurrence", 2);
        JsonNode success = objectMapper.readTree(tool.execute(toolContext(), occurrenceArguments).getContent());

        assertTrue(success.path("changed").asBoolean());
        assertEquals("value\nmiddle\nupdated\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    void editsFileUsingBackslashSeparators() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = workspaceRoot.resolve("src/main/App.java");
        Files.createDirectories(file.getParent());
        Files.write(file, "old\n".getBytes(StandardCharsets.UTF_8));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "src\\main\\App.java");
        arguments.put("search", "old");
        arguments.put("replacement", "new");

        JsonNode result = objectMapper.readTree(tool.execute(toolContext(), arguments).getContent());

        assertEquals("src/main/App.java", result.path("path").asText());
        assertEquals("new\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    private ToolContext toolContext() {
        return new ToolContext("session", "run", "turn", workspaceRoot);
    }
}

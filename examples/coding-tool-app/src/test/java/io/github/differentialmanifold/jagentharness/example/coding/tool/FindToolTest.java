package io.github.differentialmanifold.jagentharness.example.coding.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void exposesOnlyTheFileSearchSchema() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode schema = new FindTool(objectMapper, new WorkspacePathResolver()).getParametersSchema();

        assertEquals(3, schema.path("properties").size());
        assertTrue(schema.path("properties").has("pattern"));
        assertTrue(schema.path("properties").has("path"));
        assertTrue(schema.path("properties").has("limit"));
        assertFalse(schema.path("properties").has("glob"));
        assertFalse(schema.path("properties").has("type"));
        assertFalse(schema.path("properties").has("maxResults"));
        assertEquals("pattern", schema.path("required").get(0).asText());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
    }

    @Test
    void findsFilesOnlyAndSkipsCommonGeneratedDirectoriesInJavaFallback() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        FindTool tool = new FindTool(objectMapper, new WorkspacePathResolver());
        write("src/main/App.java");
        write("src/test/AppTest.java");
        write(".hidden/Config.java");
        write(".git/internal.java");
        write("target/generated/App.java");
        write("src/node_modules/package/App.java");
        Files.createDirectories(workspaceRoot.resolve("src/Directory.java"));

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("pattern", "*.java");

        JsonNode result = execute(objectMapper, tool, arguments);
        String files = result.path("files").toString();

        assertTrue(files.contains("src/main/App.java"));
        assertTrue(files.contains("src/test/AppTest.java"));
        assertTrue(files.contains(".hidden/Config.java"));
        assertFalse(files.contains(".git/internal.java"));
        assertFalse(files.contains("target/generated/App.java"));
        assertFalse(files.contains("src/node_modules/package/App.java"));
        assertFalse(files.contains("src/Directory.java"));
        assertFalse(result.path("truncated").asBoolean());
        assertEquals("java", result.path("engine").asText());
    }

    @Test
    void limitsFilesAndReportsTruncation() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        FindTool tool = new FindTool(objectMapper, new WorkspacePathResolver());
        write("one.txt");
        write("two.txt");

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("pattern", "*.txt");
        arguments.put("limit", 1);

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals(1, result.path("files").size());
        assertTrue(result.path("truncated").asBoolean());
    }

    @Test
    void acceptsBackslashPathsAndPatterns() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        FindTool tool = new FindTool(objectMapper, new WorkspacePathResolver());
        write("src/main/app/App.java");
        write("src/main/app/App.txt");

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "src\\main");
        arguments.put("pattern", "**\\*.java");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("src/main", result.path("path").asText());
        assertEquals(1, result.path("files").size());
        assertEquals("src/main/app/App.java", result.path("files").get(0).asText());
    }

    @Test
    void rejectsNegativePatterns() {
        ObjectMapper objectMapper = new ObjectMapper();
        FindTool tool = new FindTool(objectMapper, new WorkspacePathResolver());
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("pattern", "!*.java");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments));

        assertEquals("pattern must be an include glob and cannot start with !", error.getMessage());
    }

    @Test
    void rejectsDirectoryOnlyAndRootAnchoredPatterns() {
        ObjectMapper objectMapper = new ObjectMapper();
        FindTool tool = new FindTool(objectMapper, new WorkspacePathResolver());
        ObjectNode directoryOnly = objectMapper.createObjectNode();
        directoryOnly.put("pattern", "**/");
        ObjectNode rootAnchored = objectMapper.createObjectNode();
        rootAnchored.put("pattern", "/src/**");

        IllegalArgumentException directoryOnlyError = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(toolContext(), directoryOnly));
        IllegalArgumentException rootAnchoredError = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(toolContext(), rootAnchored));

        assertEquals("glob must match files and cannot end with /", directoryOnlyError.getMessage());
        assertEquals(
                "glob must be relative to path and cannot start with /",
                rootAnchoredError.getMessage());
    }

    @Test
    void pushesPatternToRipgrepAndRespectsIgnoreRules() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Optional<RipgrepExecutable> executable = new RipgrepBinaryResolver("").resolve();
        Assumptions.assumeTrue(executable.isPresent(), "ripgrep is not installed");
        FindTool tool = new FindTool(
                objectMapper,
                new WorkspacePathResolver(),
                new RipgrepSearchEngine(new RipgrepProcessRunner(), executable));
        Files.createDirectories(workspaceRoot.resolve(".git"));
        write(".gitignore", "ignored.java\n");
        write("src/App.java");
        write(".hidden/Config.java");
        write(".git/internal.java");
        write("target/generated/App.java");
        write("ignored.java");
        write("src/App.txt");

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("pattern", "*.java");

        JsonNode result = execute(objectMapper, tool, arguments);
        String files = result.path("files").toString();

        assertEquals("ripgrep", result.path("engine").asText());
        assertTrue(files.contains("src/App.java"));
        assertTrue(files.contains(".hidden/Config.java"));
        assertFalse(files.contains(".git/internal.java"));
        assertFalse(files.contains("target/generated/App.java"));
        assertFalse(files.contains("ignored.java"));
        assertFalse(files.contains("src/App.txt"));
    }

    @Test
    void pathGlobsHaveTheSameSemanticsInRipgrepAndJavaFallback() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Optional<RipgrepExecutable> executable = new RipgrepBinaryResolver("").resolve();
        Assumptions.assumeTrue(executable.isPresent(), "ripgrep is not installed");
        FindTool javaTool = new FindTool(objectMapper, new WorkspacePathResolver());
        FindTool ripgrepTool = new FindTool(
                objectMapper,
                new WorkspacePathResolver(),
                new RipgrepSearchEngine(new RipgrepProcessRunner(), executable));
        write("App.java");
        write("src/App.java");
        write("src/App7.java");
        write("src/main/App.java");
        write("src/main/App.kt");
        write("other/App.java");
        write("src/target/Generated.java");

        assertSameFiles(objectMapper, javaTool, ripgrepTool, "src/**/*.java");
        assertSameFiles(objectMapper, javaTool, ripgrepTool, "**/*.{java,kt}");
        assertSameFiles(objectMapper, javaTool, ripgrepTool, "src/App?.java");
        assertSameFiles(objectMapper, javaTool, ripgrepTool, "src/App[0-9].java");
        assertSameFiles(objectMapper, javaTool, ripgrepTool, "./src/*.java");
    }

    private JsonNode execute(ObjectMapper objectMapper, FindTool tool, ObjectNode arguments) throws Exception {
        ToolExecutionResult result = tool.execute(toolContext(), arguments);
        return objectMapper.readTree(result.getContent());
    }

    private void assertSameFiles(ObjectMapper objectMapper,
                                 FindTool javaTool,
                                 FindTool ripgrepTool,
                                 String pattern) throws Exception {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("pattern", pattern);

        JsonNode javaResult = execute(objectMapper, javaTool, arguments);
        JsonNode ripgrepResult = execute(objectMapper, ripgrepTool, arguments);

        assertEquals(javaResult.path("files"), ripgrepResult.path("files"), pattern);
        assertEquals("java", javaResult.path("engine").asText());
        assertEquals("ripgrep", ripgrepResult.path("engine").asText());
    }

    private ToolContext toolContext() {
        return new ToolContext("session", "run", "turn", workspaceRoot);
    }

    private void write(String path) throws Exception {
        write(path, "content\n");
    }

    private void write(String path, String content) throws Exception {
        Path file = workspaceRoot.resolve(path);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}

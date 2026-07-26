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

class GrepToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void exposesTheModelOrientedSearchSchema() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode schema = new GrepTool(objectMapper, new WorkspacePathResolver()).getParametersSchema();

        assertEquals(6, schema.path("properties").size());
        assertTrue(schema.path("properties").has("pattern"));
        assertTrue(schema.path("properties").has("path"));
        assertTrue(schema.path("properties").has("glob"));
        assertTrue(schema.path("properties").has("ignoreCase"));
        assertTrue(schema.path("properties").has("literal"));
        assertTrue(schema.path("properties").has("limit"));
        assertFalse(schema.path("properties").has("query"));
        assertFalse(schema.path("properties").has("caseSensitive"));
        assertFalse(schema.path("properties").has("maxResults"));
        assertEquals("pattern", schema.path("required").get(0).asText());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
    }

    @Test
    void searchesWithRegexByDefaultUsingBackslashSeparators() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());
        write("src/test/Practice.java", "class Practice { // target   text\n}\n");

        ObjectNode arguments = arguments(objectMapper, "target\\s+text", "src\\test\\Practice.java");
        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("src/test/Practice.java", result.path("path").asText());
        assertEquals("target\\s+text", result.path("pattern").asText());
        assertEquals(1, result.path("matches").size());
        assertEquals(1, result.path("matches").get(0).path("line").asInt());
        assertEquals("java", result.path("engine").asText());
    }

    @Test
    void supportsLiteralAndCaseInsensitiveSearch() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());
        write("src/App.java", "VALUE = \"A+B\";\nVALUE = \"AB\";\n");

        ObjectNode arguments = arguments(objectMapper, "a+b", ".");
        arguments.put("literal", true);
        arguments.put("ignoreCase", true);
        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals(1, result.path("matches").size());
        assertEquals(1, result.path("matches").get(0).path("line").asInt());
    }

    @Test
    void filtersFilesWithGlobInJavaFallback() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());
        write("src/main/App.java", "needle\n");
        write("src/main/ignored.txt", "needle\n");
        write(".git/internal.java", "needle\n");
        write("target/generated/App.java", "needle\n");

        ObjectNode arguments = arguments(objectMapper, "needle", ".");
        arguments.put("literal", true);
        arguments.put("glob", "src\\**\\*.java");
        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals(1, result.path("matches").size());
        assertEquals("src/main/App.java", result.path("matches").get(0).path("path").asText());
    }

    @Test
    void reportsMissingFileOrDirectory() {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(
                        toolContext(),
                        arguments(objectMapper, "needle", "missing\\Practice.java")));

        assertEquals("Path not found: missing/Practice.java", error.getMessage());
    }

    @Test
    void rejectsMultilinePatterns() {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments(objectMapper, "one\ntwo", ".")));

        assertEquals("pattern must be single-line", error.getMessage());
    }

    @Test
    void rejectsInvalidRegexInJavaFallback() {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments(objectMapper, "[", ".")));

        assertTrue(error.getMessage().startsWith("Invalid regular expression:"));
    }

    @Test
    void rejectsRegexFeaturesThatRipgrepCannotRunPortably() {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());

        IllegalArgumentException lookAround = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments(objectMapper, "(?<=one)two", ".")));
        IllegalArgumentException backreference = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments(objectMapper, "(one)\\1", ".")));
        IllegalArgumentException javaWhitespace = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments(objectMapper, "\\h+", ".")));
        IllegalArgumentException lineBreak = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments(objectMapper, "\\R", ".")));
        IllegalArgumentException javaFlag = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments(objectMapper, "(?d)one", ".")));

        assertTrue(lookAround.getMessage().endsWith("look-around"));
        assertTrue(backreference.getMessage().endsWith("backreferences"));
        assertTrue(javaWhitespace.getMessage().endsWith("unsupported escapes"));
        assertTrue(lineBreak.getMessage().endsWith("unsupported escapes"));
        assertTrue(javaFlag.getMessage().endsWith("unsupported inline flags"));
    }

    @Test
    void limitsJavaFallbackResultsAndReportsTruncation() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GrepTool tool = new GrepTool(objectMapper, new WorkspacePathResolver());
        write("one.txt", "needle\n");
        write("two.txt", "needle\n");
        ObjectNode arguments = arguments(objectMapper, "needle", ".");
        arguments.put("literal", true);
        arguments.put("limit", 1);

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals(1, result.path("matches").size());
        assertEquals(1, result.path("limit").asInt());
        assertEquals("java", result.path("engine").asText());
        assertTrue(result.path("truncated").asBoolean());
    }

    @Test
    void pushesRegexGlobAndIgnorePolicyToRipgrep() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Optional<RipgrepExecutable> executable = new RipgrepBinaryResolver("").resolve();
        Assumptions.assumeTrue(executable.isPresent(), "ripgrep is not installed");
        GrepTool tool = new GrepTool(
                objectMapper,
                new WorkspacePathResolver(),
                new RipgrepSearchEngine(new RipgrepProcessRunner(), executable));
        Files.createDirectories(workspaceRoot.resolve(".git"));
        write(".gitignore", "ignored.java\n");
        write("src/App.java", "ab\n");
        write(".hidden/Config.java", "aab\n");
        write(".git/internal.java", "ab\n");
        write("target/generated/App.java", "ab\n");
        write("ignored.java", "ab\n");
        write("src/App.txt", "ab\n");

        ObjectNode arguments = arguments(objectMapper, "a+b", ".");
        arguments.put("glob", "*.java");
        JsonNode result = execute(objectMapper, tool, arguments);
        String matches = result.path("matches").toString();

        assertEquals("ripgrep", result.path("engine").asText());
        assertEquals(2, result.path("matches").size());
        assertTrue(matches.contains("src/App.java"));
        assertTrue(matches.contains(".hidden/Config.java"));
        assertFalse(matches.contains(".git/internal.java"));
        assertFalse(matches.contains("target/generated/App.java"));
        assertFalse(matches.contains("ignored.java"));
        assertFalse(matches.contains("src/App.txt"));
    }

    @Test
    void pathGlobsHaveTheSameSemanticsInRipgrepAndJavaFallback() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Optional<RipgrepExecutable> executable = new RipgrepBinaryResolver("").resolve();
        Assumptions.assumeTrue(executable.isPresent(), "ripgrep is not installed");
        GrepTool javaTool = new GrepTool(objectMapper, new WorkspacePathResolver());
        GrepTool ripgrepTool = new GrepTool(
                objectMapper,
                new WorkspacePathResolver(),
                new RipgrepSearchEngine(new RipgrepProcessRunner(), executable));
        write("src/App.java", "needle\n");
        write("src/main/App.java", "needle\n");
        write("src/main/App.kt", "needle\n");
        write("other/App.java", "needle\n");
        write("src/build/Generated.java", "needle\n");

        ObjectNode arguments = arguments(objectMapper, "needle", ".");
        arguments.put("literal", true);
        arguments.put("glob", "src/**/*.java");

        JsonNode javaResult = execute(objectMapper, javaTool, arguments);
        JsonNode ripgrepResult = execute(objectMapper, ripgrepTool, arguments);

        assertEquals(2, javaResult.path("matches").size());
        assertEquals(javaResult.path("matches"), ripgrepResult.path("matches"));
        assertEquals("java", javaResult.path("engine").asText());
        assertEquals("ripgrep", ripgrepResult.path("engine").asText());
    }

    @Test
    void unicodeCharacterClassesMatchInRipgrepAndJavaFallback() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Optional<RipgrepExecutable> executable = new RipgrepBinaryResolver("").resolve();
        Assumptions.assumeTrue(executable.isPresent(), "ripgrep is not installed");
        GrepTool javaTool = new GrepTool(objectMapper, new WorkspacePathResolver());
        GrepTool ripgrepTool = new GrepTool(
                objectMapper,
                new WorkspacePathResolver(),
                new RipgrepSearchEngine(new RipgrepProcessRunner(), executable));
        write("unicode.txt", "value ٣\n");
        ObjectNode arguments = arguments(objectMapper, "\\d+", ".");

        JsonNode javaResult = execute(objectMapper, javaTool, arguments);
        JsonNode ripgrepResult = execute(objectMapper, ripgrepTool, arguments);

        assertEquals(1, javaResult.path("matches").size());
        assertEquals(javaResult.path("matches"), ripgrepResult.path("matches"));
    }

    @Test
    void binaryAndCrLfFilesMatchInRipgrepAndJavaFallback() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Optional<RipgrepExecutable> executable = new RipgrepBinaryResolver("").resolve();
        Assumptions.assumeTrue(executable.isPresent(), "ripgrep is not installed");
        GrepTool javaTool = new GrepTool(objectMapper, new WorkspacePathResolver());
        GrepTool ripgrepTool = new GrepTool(
                objectMapper,
                new WorkspacePathResolver(),
                new RipgrepSearchEngine(new RipgrepProcessRunner(), executable));
        Files.write(
                workspaceRoot.resolve("binary.dat"),
                new byte[] { 0, 'n', 'e', 'e', 'd', 'l', 'e', '\n' });
        write("windows.txt", "foo\r\nbar\r\n");

        ObjectNode binaryArguments = arguments(objectMapper, "needle", "binary.dat");
        binaryArguments.put("literal", true);
        JsonNode javaBinary = execute(objectMapper, javaTool, binaryArguments);
        JsonNode ripgrepBinary = execute(objectMapper, ripgrepTool, binaryArguments);

        ObjectNode crlfArguments = arguments(objectMapper, "^foo$", "windows.txt");
        JsonNode javaCrlf = execute(objectMapper, javaTool, crlfArguments);
        JsonNode ripgrepCrlf = execute(objectMapper, ripgrepTool, crlfArguments);

        assertEquals(1, javaBinary.path("matches").size());
        assertEquals(javaBinary.path("matches"), ripgrepBinary.path("matches"));
        assertEquals(1, javaCrlf.path("matches").size());
        assertEquals(javaCrlf.path("matches"), ripgrepCrlf.path("matches"));
    }

    @Test
    void keepsLiteralSemanticsWhenUsingRipgrep() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Optional<RipgrepExecutable> executable = new RipgrepBinaryResolver("").resolve();
        Assumptions.assumeTrue(executable.isPresent(), "ripgrep is not installed");
        GrepTool tool = new GrepTool(
                objectMapper,
                new WorkspacePathResolver(),
                new RipgrepSearchEngine(new RipgrepProcessRunner(), executable));
        write("src/App.java", "a+b\nab\n");

        ObjectNode arguments = arguments(objectMapper, "a+b", ".");
        arguments.put("literal", true);
        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("ripgrep", result.path("engine").asText());
        assertEquals(1, result.path("matches").size());
        assertEquals(1, result.path("matches").get(0).path("line").asInt());
    }

    private JsonNode execute(ObjectMapper objectMapper, GrepTool tool, ObjectNode arguments) throws Exception {
        ToolExecutionResult result = tool.execute(toolContext(), arguments);
        return objectMapper.readTree(result.getContent());
    }

    private ObjectNode arguments(ObjectMapper objectMapper, String pattern, String path) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("pattern", pattern);
        arguments.put("path", path);
        return arguments;
    }

    private ToolContext toolContext() {
        return new ToolContext("session", "run", "turn", workspaceRoot);
    }

    private void write(String relativePath, String content) throws Exception {
        Path path = workspaceRoot.resolve(relativePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}

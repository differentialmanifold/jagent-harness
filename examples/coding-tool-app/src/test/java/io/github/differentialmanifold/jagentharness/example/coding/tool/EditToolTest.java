package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalDecision;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalMode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRejectedException;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.ContentHashing;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditToolTest {

    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf};

    @TempDir
    Path workspaceRoot;

    @Test
    void exposesOnlyTheBatchEditSchema() {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());

        JsonNode schema = tool.getParametersSchema();
        JsonNode properties = schema.path("properties");
        JsonNode edits = properties.path("edits");
        JsonNode item = edits.path("items");

        assertEquals(2, properties.size());
        assertTrue(properties.has("path"));
        assertTrue(properties.has("edits"));
        assertFalse(properties.has("expectedHash"));
        assertFalse(properties.has("search"));
        assertFalse(properties.has("startLine"));
        assertEquals("array", edits.path("type").asText());
        assertEquals(1, edits.path("minItems").asInt());
        assertTrue(item.path("properties").has("oldText"));
        assertTrue(item.path("properties").has("newText"));
        assertFalse(item.path("additionalProperties").asBoolean(true));
        assertEquals(2, item.path("required").size());
    }

    @Test
    void returnsStructuredDiffForReplacement() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("App.vue", "line one\nconst oldValue = true\nline three\n");

        ObjectNode arguments = arguments(objectMapper, "App.vue");
        addEdit(arguments, "const oldValue = true", "const newValue = true\nconst otherValue = false");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("App.vue", result.path("path").asText());
        assertEquals("App.vue", result.path("fileName").asText());
        assertEquals("exact", result.path("appliedEdits").get(0).path("matchStrategy").asText());
        assertEquals(2, result.path("additions").asInt());
        assertEquals(1, result.path("deletions").asInt());
        assertEquals("line one\nconst newValue = true\nconst otherValue = false\nline three\n", read(file));
        assertEquals(ContentHashing.sha256(Files.readAllBytes(file)), result.path("contentHash").asText());
    }

    @Test
    void appliesMultipleDistantEditsAgainstOneSnapshotAndWritesOnce() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write(
                "batch.txt",
                "old alpha\none\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nold omega\n");

        ObjectNode arguments = arguments(objectMapper, "batch.txt");
        addEdit(arguments, "old alpha", "new alpha");
        addEdit(arguments, "old omega", "new omega");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertTrue(result.path("changed").asBoolean());
        assertEquals(2, result.path("editCount").asInt());
        assertEquals(2, result.path("diff").path("hunks").size());
        assertEquals(2, result.path("additions").asInt());
        assertEquals(2, result.path("deletions").asInt());
        assertEquals(
                "new alpha\none\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnew omega\n",
                read(file));
    }

    @Test
    void validatesEveryEditAgainstTheOriginalSnapshotWithoutPartialWrites() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("snapshot.txt", "first\nsecond\n");

        ObjectNode arguments = arguments(objectMapper, "snapshot.txt");
        addEdit(arguments, "first", "generated");
        addEdit(arguments, "generated", "done");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("SEARCH_NOT_FOUND", result.path("code").asText());
        assertEquals(1, result.path("editIndex").asInt());
        assertEquals("first\nsecond\n", read(file));
    }

    @Test
    void replacesReadStyleTextInCrlfFileWithoutRewritingOtherBytes() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("windows.txt", "one\r\ntwo\r\nthree\r\n");

        ObjectNode arguments = arguments(objectMapper, "windows.txt");
        addEdit(arguments, "two\nthree\n", "new two\nnew three\n");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("line_endings", result.path("appliedEdits").get(0).path("matchStrategy").asText());
        assertEquals(2, result.path("appliedEdits").get(0).path("startLine").asInt());
        assertEquals(3, result.path("appliedEdits").get(0).path("endLine").asInt());
        assertEquals("one\r\nnew two\r\nnew three\r\n", read(file));
    }

    @Test
    void preservesUntouchedMixedLineEndings() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("mixed.txt", "one\r\ntwo\nthree\r\nfour\n");

        ObjectNode arguments = arguments(objectMapper, "mixed.txt");
        addEdit(arguments, "two\nthree\n", "new two\nnew three\n");

        execute(objectMapper, tool, arguments);

        assertEquals("one\r\nnew two\nnew three\nfour\n", read(file));
    }

    @Test
    void preservesUtf8BomWhileEditingTheFirstLine() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = workspaceRoot.resolve("bom.txt");
        byte[] content = "first\r\nsecond\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] original = new byte[UTF8_BOM.length + content.length];
        System.arraycopy(UTF8_BOM, 0, original, 0, UTF8_BOM.length);
        System.arraycopy(content, 0, original, UTF8_BOM.length, content.length);
        Files.write(file, original);

        ObjectNode arguments = arguments(objectMapper, "bom.txt");
        addEdit(arguments, "first\n", "updated\n");

        execute(objectMapper, tool, arguments);

        byte[] updated = Files.readAllBytes(file);
        assertArrayEquals(UTF8_BOM, new byte[]{updated[0], updated[1], updated[2]});
        assertEquals("updated\r\nsecond\r\n",
                new String(updated, UTF8_BOM.length, updated.length - UTF8_BOM.length, StandardCharsets.UTF_8));
    }

    @Test
    void usesConservativeTrailingWhitespaceAndIndentationFallbacks() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path trailing = write("trailing.txt", "one  \ntwo\n");

        ObjectNode trailingArguments = arguments(objectMapper, "trailing.txt");
        addEdit(trailingArguments, "one\n", "updated\n");
        JsonNode trailingResult = execute(objectMapper, tool, trailingArguments);

        assertEquals("trailing_whitespace",
                trailingResult.path("appliedEdits").get(0).path("matchStrategy").asText());
        assertEquals("updated\ntwo\n", read(trailing));

        Path indented = write("indent.java", "class X {\n        if (ready) {\n            run();\n        }\n}\n");
        ObjectNode indentationArguments = arguments(objectMapper, "indent.java");
        addEdit(
                indentationArguments,
                "if (ready) {\n    run();\n}",
                "if (ready) {\n    stop();\n}");
        JsonNode indentationResult = execute(objectMapper, tool, indentationArguments);

        assertEquals("common_indentation",
                indentationResult.path("appliedEdits").get(0).path("matchStrategy").asText());
        assertEquals("class X {\n        if (ready) {\n            stop();\n        }\n}\n", read(indented));
    }

    @Test
    void doesNotDropAnUnterminatedWhitespaceOnlyLineDuringFallback() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("whitespace-boundary.txt", "foo\nbar\n");
        ObjectNode arguments = arguments(objectMapper, "whitespace-boundary.txt");
        addEdit(arguments, "foo\n    ", "changed");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("SEARCH_NOT_FOUND", result.path("code").asText());
        assertEquals("foo\nbar\n", read(file));
    }

    @Test
    void usesTheTargetLinesLocalEndingStyleForInsertedLines() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("local-eol.txt", "first\r\nlf target\nlast\n");
        ObjectNode arguments = arguments(objectMapper, "local-eol.txt");
        addEdit(arguments, "target", "target\ninserted");

        execute(objectMapper, tool, arguments);

        assertEquals("first\r\nlf target\ninserted\nlast\n", read(file));
    }

    @Test
    void reportsOneChangedLineForTwoEditsOnTheSameLine() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("same-line.txt", "alpha beta gamma\n");
        ObjectNode arguments = arguments(objectMapper, "same-line.txt");
        addEdit(arguments, "alpha", "ALPHA");
        addEdit(arguments, "gamma", "GAMMA");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals(1, result.path("additions").asInt());
        assertEquals(1, result.path("deletions").asInt());
        assertEquals(1, result.path("diff").path("hunks").size());
        assertEquals("ALPHA beta GAMMA\n", read(file));
    }

    @Test
    void includesEndOfFileNewlineChangesInTheDiff() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("eof-newline.txt", "foo");
        ObjectNode arguments = arguments(objectMapper, "eof-newline.txt");
        addEdit(arguments, "foo", "foo\n");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertTrue(result.path("changed").asBoolean());
        assertEquals(1, result.path("additions").asInt());
        assertEquals(1, result.path("deletions").asInt());
        JsonNode lines = result.path("diff").path("hunks").get(0).path("lines");
        assertFalse(lines.get(0).path("lineTerminated").asBoolean());
        assertTrue(lines.get(1).path("lineTerminated").asBoolean());
        assertEquals("foo\n", read(file));
    }

    @Test
    void rejectsOverlappingAndAmbiguousMatches() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path ambiguous = write("ambiguous.txt", "aaa\n");

        ObjectNode ambiguousArguments = arguments(objectMapper, "ambiguous.txt");
        addEdit(ambiguousArguments, "aa", "x");
        JsonNode ambiguousResult = execute(objectMapper, tool, ambiguousArguments);

        assertEquals("AMBIGUOUS_MATCH", ambiguousResult.path("code").asText());
        assertEquals(2, ambiguousResult.path("matchCount").asInt());
        assertEquals("aaa\n", read(ambiguous));

        Path overlapping = write("overlap.txt", "abcdef\n");
        ObjectNode overlapArguments = arguments(objectMapper, "overlap.txt");
        addEdit(overlapArguments, "bcd", "x");
        addEdit(overlapArguments, "cde", "y");
        JsonNode overlapResult = execute(objectMapper, tool, overlapArguments);

        assertEquals("OVERLAPPING_EDITS", overlapResult.path("code").asText());
        assertEquals(2, overlapResult.path("conflictingEditIndexes").size());
        assertEquals("abcdef\n", read(overlapping));
    }

    @Test
    void treatsDifferentPhysicalLineEndingsAsAmbiguousLogicalMatches() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("eol-ambiguous.txt", "value\nmiddle\r\nvalue\r\n");

        ObjectNode arguments = arguments(objectMapper, "eol-ambiguous.txt");
        addEdit(arguments, "value\n", "updated\n");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("AMBIGUOUS_MATCH", result.path("code").asText());
        assertEquals(2, result.path("matchCount").asInt());
        assertEquals("value\nmiddle\r\nvalue\r\n", read(file));
    }

    @Test
    void supportsDeletionAndRejectsLegacyModes() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("delete.txt", "keep\nremove\n");

        ObjectNode arguments = arguments(objectMapper, "delete.txt");
        addEdit(arguments, "remove\n", "");
        execute(objectMapper, tool, arguments);
        assertEquals("keep\n", read(file));

        ObjectNode legacy = objectMapper.createObjectNode();
        legacy.put("path", "delete.txt");
        legacy.put("search", "keep");
        legacy.put("replacement", "changed");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(toolContext(), legacy));
        assertEquals("keep\n", read(file));
    }

    @Test
    void reportsHardLinksInsteadOfSilentlyDetachingTheEditedPath() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("hard-linked.txt", "old\n");
        Path alias = workspaceRoot.resolve("hard-linked-alias.txt");
        try {
            Files.createLink(alias, file);
            Object linkCount = Files.getAttribute(file, "unix:nlink");
            Assumptions.assumeTrue(((Number) linkCount).longValue() > 1L);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Hard-link count is unavailable");
        }
        ObjectNode arguments = arguments(objectMapper, "hard-linked.txt");
        addEdit(arguments, "old", "new");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("HARD_LINK_UNSUPPORTED", result.path("code").asText());
        assertTrue(result.path("linkCount").asLong() > 1L);
        assertEquals("old\n", read(file));
        assertEquals("old\n", read(alias));
    }

    @Test
    void rebasesOnceWhenOnlyUnrelatedContentChangesDuringExecution() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path file = write("rebase.txt", "old\n");
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver()) {
            private int reads;

            @Override
            byte[] readFile(Path path) throws IOException {
                reads++;
                if (reads == 2) {
                    Files.write(path, "external\nold\n".getBytes(StandardCharsets.UTF_8));
                }
                return super.readFile(path);
            }
        };
        ObjectNode arguments = arguments(objectMapper, "rebase.txt");
        addEdit(arguments, "old", "new");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertTrue(result.path("rebased").asBoolean());
        assertEquals("external\nnew\n", read(file));
    }

    @Test
    void reportsConcurrentModificationAfterASecondChange() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path file = write("conflict.txt", "old\n");
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver()) {
            private int reads;

            @Override
            byte[] readFile(Path path) throws IOException {
                reads++;
                if (reads == 2) {
                    Files.write(path, "external one\nold\n".getBytes(StandardCharsets.UTF_8));
                } else if (reads == 3) {
                    Files.write(path, "external two\nold\n".getBytes(StandardCharsets.UTF_8));
                }
                return super.readFile(path);
            }
        };
        ObjectNode arguments = arguments(objectMapper, "conflict.txt");
        addEdit(arguments, "old", "new");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("CONCURRENT_MODIFICATION", result.path("code").asText());
        assertEquals("external two\nold\n", read(file));
    }

    @Test
    void rejectsInvalidUtf8AndNulWithoutChangingTheFile() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path invalid = workspaceRoot.resolve("invalid.txt");
        byte[] invalidBytes = new byte[]{(byte) 0xc3, 0x28};
        Files.write(invalid, invalidBytes);
        ObjectNode invalidArguments = arguments(objectMapper, "invalid.txt");
        addEdit(invalidArguments, "x", "y");

        assertThrows(IllegalArgumentException.class, () -> tool.execute(toolContext(), invalidArguments));
        assertArrayEquals(invalidBytes, Files.readAllBytes(invalid));

        Path nul = workspaceRoot.resolve("nul.txt");
        byte[] nulBytes = new byte[]{'a', 0, 'b'};
        Files.write(nul, nulBytes);
        ObjectNode nulArguments = arguments(objectMapper, "nul.txt");
        addEdit(nulArguments, "a", "x");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(toolContext(), nulArguments));
        assertArrayEquals(nulBytes, Files.readAllBytes(nul));
    }

    @Test
    void rejectsInvalidReplacementTextBeforeWriting() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("invalid-replacement.txt", "old\n");

        ObjectNode nulArguments = arguments(objectMapper, "invalid-replacement.txt");
        addEdit(nulArguments, "old", "new\0value");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(toolContext(), nulArguments));
        assertEquals("old\n", read(file));

        ObjectNode surrogateArguments = arguments(objectMapper, "invalid-replacement.txt");
        addEdit(surrogateArguments, "old", "\uD800");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(toolContext(), surrogateArguments));
        assertEquals("old\n", read(file));
    }

    @Test
    void editsFileUsingBackslashSeparators() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path file = write("src/main/App.java", "old\n");
        ObjectNode arguments = arguments(objectMapper, "src\\main\\App.java");
        addEdit(arguments, "old", "new");

        JsonNode result = execute(objectMapper, tool, arguments);

        assertEquals("src/main/App.java", result.path("path").asText());
        assertEquals("new\n", read(file));
    }

    @Test
    void requiresApprovalForWorkspaceSymlinkThatTargetsOutside() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EditTool tool = new EditTool(objectMapper, new WorkspacePathResolver());
        Path outside = Files.createTempFile("jagent-edit-symlink", ".txt");
        Files.write(outside, "old\n".getBytes(StandardCharsets.UTF_8));
        Path link = workspaceRoot.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }
        AtomicReference<ToolApprovalRequest> approval = new AtomicReference<ToolApprovalRequest>();
        ObjectNode arguments = arguments(objectMapper, "linked.txt");
        addEdit(arguments, "old", "new");

        assertThrows(
                ToolApprovalRejectedException.class,
                () -> tool.execute(
                        approvalContext(approval, ToolApprovalDecision.denied("denied")),
                        arguments));

        assertEquals(outside.toRealPath().toString(), approval.get().getTarget());
        assertEquals("old\n", read(outside));
    }

    private JsonNode execute(ObjectMapper objectMapper, EditTool tool, ObjectNode arguments) throws Exception {
        ToolExecutionResult executionResult = tool.execute(toolContext(), arguments);
        return objectMapper.readTree(executionResult.getContent());
    }

    private ObjectNode arguments(ObjectMapper objectMapper, String path) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", path);
        arguments.putArray("edits");
        return arguments;
    }

    private void addEdit(ObjectNode arguments, String oldText, String newText) {
        ArrayNode edits = (ArrayNode) arguments.path("edits");
        ObjectNode edit = edits.addObject();
        edit.put("oldText", oldText);
        edit.put("newText", newText);
    }

    private ToolContext toolContext() {
        return new ToolContext("session", "run", "turn", workspaceRoot);
    }

    private ToolContext approvalContext(AtomicReference<ToolApprovalRequest> request,
                                        ToolApprovalDecision decision) {
        return new ToolContext(
                "session",
                "run",
                "turn",
                null,
                workspaceRoot,
                null,
                Collections.emptyMap(),
                StopSignal.none(),
                ToolApprovalMode.ASK_FOR_APPROVAL,
                (approvalRequest, stopSignal) -> {
                    request.set(approvalRequest);
                    return decision;
                },
                "call-1",
                "edit");
    }

    private Path write(String relativePath, String content) throws IOException {
        Path path = workspaceRoot.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

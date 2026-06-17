package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class EditTool implements ToolDefinition {

    private static final int DIFF_CONTEXT_LINES = 3;

    private final ObjectMapper objectMapper;
    private final WorkspacePathResolver pathResolver;

    public EditTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        this.objectMapper = objectMapper;
        this.pathResolver = pathResolver;
    }

    @Override
    public String getName() {
        return "edit";
    }

    @Override
    public String getDescription() {
        return "Modify an existing UTF-8 file. Relative paths resolve from the workspace; absolute paths are allowed. Supports exact replacement, line-range replacement or deletion, and insertion before or after a line. Prefer this over write for localized changes.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(objectMapper, "Workspace-relative or absolute file path."));
        properties.set("search", ToolSchemas.stringProperty(objectMapper, "Exact text to replace. Use with replacement for exact replacement mode."));
        properties.set("replacement", ToolSchemas.stringProperty(objectMapper, "Replacement or inserted text. Use an empty string to delete a line range."));
        properties.set("all", ToolSchemas.booleanProperty(objectMapper, "Replace every occurrence. Default false."));
        properties.set("startLine", ToolSchemas.integerProperty(objectMapper, "One-based first line for line-range replacement or deletion."));
        properties.set("endLine", ToolSchemas.integerProperty(objectMapper, "One-based last line for line-range replacement or deletion."));
        properties.set("insertBeforeLine", ToolSchemas.integerProperty(objectMapper, "Insert replacement before this one-based line number."));
        properties.set("insertAfterLine", ToolSchemas.integerProperty(objectMapper, "Insert replacement after this one-based line number. Use 0 to insert at the beginning."));
        return ToolSchemas.objectSchema(objectMapper, properties, "path");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        Path path = pathResolver.resolve(context, ToolArguments.requiredText(arguments, "path"));
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found: " + pathResolver.relative(context, path));
        }
        requireApprovalIfOutsideWorkspace(context, path);
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String updated = applyEdit(context, path, arguments, content);
        Files.write(path, updated.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);

        boolean changed = !content.equals(updated);
        DiffSummary diff = changed
                ? buildDiff(content, updated)
                : new DiffSummary(0, 0, objectMapper.createArrayNode());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", pathResolver.relative(context, path));
        result.put("fileName", path.getFileName().toString());
        result.put("changed", changed);
        result.put("additions", diff.additions);
        result.put("deletions", diff.deletions);
        ObjectNode diffNode = objectMapper.createObjectNode();
        diffNode.set("hunks", diff.hunks);
        result.set("diff", diffNode);
        return ToolExecutionResult.of(result.toString());
    }

    private void requireApprovalIfOutsideWorkspace(ToolContext context, Path path) throws Exception {
        if (pathResolver.isInsideWorkspace(context, path)) {
            return;
        }
        context.requestApproval(new ToolApprovalRequest(
                "Approve edit outside workspace",
                "The edit tool wants to modify a file outside the current workspace.",
                "edit",
                path.toAbsolutePath().normalize().toString()));
    }

    private String applyEdit(ToolContext context, Path path, JsonNode arguments, String content) {
        boolean hasSearch = has(arguments, "search");
        boolean hasLineRange = has(arguments, "startLine") || has(arguments, "endLine");
        boolean hasInsertBefore = has(arguments, "insertBeforeLine");
        boolean hasInsertAfter = has(arguments, "insertAfterLine");
        int modes = (hasSearch ? 1 : 0)
                + (hasLineRange ? 1 : 0)
                + (hasInsertBefore ? 1 : 0)
                + (hasInsertAfter ? 1 : 0);
        if (modes != 1) {
            throw new IllegalArgumentException(
                    "Use exactly one edit mode: search replacement, startLine/endLine range, insertBeforeLine, or insertAfterLine");
        }

        String replacement = optionalString(arguments, "replacement");
        if (hasSearch) {
            String search = ToolArguments.requiredText(arguments, "search");
            if (replacement == null) {
                throw new IllegalArgumentException("Missing required argument: replacement");
            }
            if (!containsText(content, search)) {
                throw new IllegalArgumentException("Search text not found in " + pathResolver.relative(context, path));
            }
            return replaceText(content, search, replacement, arguments.path("all").asBoolean(false));
        }

        if (hasLineRange) {
            int startLine = requiredLine(arguments, "startLine");
            int endLine = requiredLine(arguments, "endLine");
            if (startLine > endLine) {
                throw new IllegalArgumentException("startLine must be less than or equal to endLine");
            }
            return replaceLineRange(content, startLine, endLine, replacement == null ? "" : replacement);
        }

        if (replacement == null) {
            throw new IllegalArgumentException("Missing required argument: replacement");
        }
        if (hasInsertBefore) {
            return insertBeforeLine(content, requiredLine(arguments, "insertBeforeLine"), replacement);
        }
        return insertAfterLine(content, requiredLine(arguments, "insertAfterLine"), replacement);
    }

    private String replaceText(String content,
                               String search,
                               String replacement,
                               boolean all) {
        String lineSeparator = detectLineSeparator(content);
        String effectiveReplacement = normalizeLineEndings(replacement, lineSeparator);
        if (!content.contains(search)) {
            String logicalContent = normalizeLineEndings(content, "\n");
            String logicalSearch = normalizeLineEndings(search, "\n");
            if (!logicalContent.contains(logicalSearch)) {
                return content;
            }
            String logicalReplacement = normalizeLineEndings(replacement, "\n");
            return normalizeLineEndings(
                    replaceTextExact(logicalContent, logicalSearch, logicalReplacement, all),
                    lineSeparator);
        }
        return replaceTextExact(content, search, effectiveReplacement, all);
    }

    private boolean containsText(String content, String search) {
        return content.contains(search)
                || normalizeLineEndings(content, "\n").contains(normalizeLineEndings(search, "\n"));
    }

    private String replaceTextExact(String content,
                                    String search,
                                    String replacement,
                                    boolean all) {
        StringBuilder builder = new StringBuilder(content.length() + replacement.length());
        int cursor = 0;
        while (cursor <= content.length()) {
            int index = content.indexOf(search, cursor);
            if (index < 0) {
                builder.append(content.substring(cursor));
                break;
            }

            builder.append(content, cursor, index);
            builder.append(replacement);

            cursor = index + search.length();
            if (!all) {
                builder.append(content.substring(cursor));
                break;
            }
        }
        return builder.toString();
    }

    private String replaceLineRange(String content, int startLine, int endLine, String replacement) {
        String lineSeparator = detectLineSeparator(content);
        List<String> lines = splitLines(content);
        requireLineRange(lines, startLine, endLine);
        List<String> updated = new ArrayList<String>();
        updated.addAll(lines.subList(0, startLine - 1));
        updated.addAll(splitLines(replacement));
        updated.addAll(lines.subList(endLine, lines.size()));
        return joinLines(updated, hasTrailingLineSeparator(content), lineSeparator);
    }

    private String insertBeforeLine(String content, int line, String replacement) {
        String lineSeparator = detectLineSeparator(content);
        List<String> lines = splitLines(content);
        if (line < 1 || line > lines.size() + 1) {
            throw new IllegalArgumentException("insertBeforeLine must be between 1 and " + (lines.size() + 1));
        }
        List<String> updated = new ArrayList<String>();
        updated.addAll(lines.subList(0, line - 1));
        updated.addAll(splitLines(replacement));
        updated.addAll(lines.subList(line - 1, lines.size()));
        return joinLines(updated, shouldEndWithNewline(content, replacement), lineSeparator);
    }

    private String insertAfterLine(String content, int line, String replacement) {
        String lineSeparator = detectLineSeparator(content);
        List<String> lines = splitLines(content);
        if (line < 0 || line > lines.size()) {
            throw new IllegalArgumentException("insertAfterLine must be between 0 and " + lines.size());
        }
        List<String> updated = new ArrayList<String>();
        updated.addAll(lines.subList(0, line));
        updated.addAll(splitLines(replacement));
        updated.addAll(lines.subList(line, lines.size()));
        return joinLines(updated, shouldEndWithNewline(content, replacement), lineSeparator);
    }

    private DiffSummary buildDiff(String before, String after) {
        List<String> oldLines = splitLines(before);
        List<String> newLines = splitLines(after);
        ArrayNode hunks = objectMapper.createArrayNode();

        int prefix = commonPrefixLines(oldLines, newLines);
        int suffix = commonSuffixLines(oldLines, newLines, prefix);
        int oldChangeEnd = oldLines.size() - suffix;
        int newChangeEnd = newLines.size() - suffix;
        int deletions = oldChangeEnd - prefix;
        int additions = newChangeEnd - prefix;
        int beforeContext = Math.min(DIFF_CONTEXT_LINES, prefix);
        int afterContext = Math.min(DIFF_CONTEXT_LINES,
                Math.min(oldLines.size() - oldChangeEnd, newLines.size() - newChangeEnd));

        ObjectNode hunk = objectMapper.createObjectNode();
        hunk.put("oldStart", Math.max(1, prefix - beforeContext + 1));
        hunk.put("oldLines", beforeContext + deletions + afterContext);
        hunk.put("newStart", Math.max(1, prefix - beforeContext + 1));
        hunk.put("newLines", beforeContext + additions + afterContext);

        ArrayNode lines = objectMapper.createArrayNode();
        for (int i = prefix - beforeContext; i < prefix; i++) {
            appendDiffLine(lines, "context", i + 1, i + 1, oldLines.get(i));
        }
        for (int i = prefix; i < oldChangeEnd; i++) {
            appendDiffLine(lines, "removed", i + 1, null, oldLines.get(i));
        }
        for (int i = prefix; i < newChangeEnd; i++) {
            appendDiffLine(lines, "added", null, i + 1, newLines.get(i));
        }
        for (int i = 0; i < afterContext; i++) {
            int oldIndex = oldChangeEnd + i;
            int newIndex = newChangeEnd + i;
            appendDiffLine(lines, "context", oldIndex + 1, newIndex + 1, oldLines.get(oldIndex));
        }

        hunk.set("lines", lines);
        hunks.add(hunk);
        return new DiffSummary(additions, deletions, hunks);
    }

    private void appendDiffLine(ArrayNode lines,
                                String type,
                                Integer oldLine,
                                Integer newLine,
                                String content) {
        ObjectNode line = objectMapper.createObjectNode();
        line.put("type", type);
        if (oldLine == null) {
            line.putNull("oldLine");
        } else {
            line.put("oldLine", oldLine);
        }
        if (newLine == null) {
            line.putNull("newLine");
        } else {
            line.put("newLine", newLine);
        }
        line.put("content", content);
        lines.add(line);
    }

    private List<String> splitLines(String value) {
        List<String> lines = new ArrayList<String>();
        if (value == null || value.isEmpty()) {
            return lines;
        }

        int start = 0;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\r' || current == '\n') {
                lines.add(value.substring(start, index));
                if (current == '\r'
                        && index + 1 < value.length()
                        && value.charAt(index + 1) == '\n') {
                    index++;
                }
                start = index + 1;
            }
            index++;
        }
        if (start < value.length()) {
            lines.add(value.substring(start));
        }
        return lines;
    }

    private String joinLines(List<String> lines, boolean trailingNewline, String lineSeparator) {
        if (lines.isEmpty()) {
            return "";
        }
        String result = String.join(lineSeparator, lines);
        return trailingNewline ? result + lineSeparator : result;
    }

    private boolean shouldEndWithNewline(String content, String replacement) {
        return hasTrailingLineSeparator(content) || hasTrailingLineSeparator(replacement);
    }

    private boolean hasTrailingLineSeparator(String value) {
        return value != null && !value.isEmpty()
                && (value.endsWith("\n") || value.endsWith("\r"));
    }

    private String detectLineSeparator(String content) {
        if (content == null || content.isEmpty()) {
            return "\n";
        }
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (current == '\n') {
                return "\n";
            }
            if (current == '\r') {
                return i + 1 < content.length() && content.charAt(i + 1) == '\n'
                        ? "\r\n"
                        : "\r";
            }
        }
        return "\n";
    }

    private String normalizeLineEndings(String value, String lineSeparator) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\r') {
                if (i + 1 < value.length() && value.charAt(i + 1) == '\n') {
                    i++;
                }
                result.append(lineSeparator);
            } else if (current == '\n') {
                result.append(lineSeparator);
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private void requireLineRange(List<String> lines, int startLine, int endLine) {
        if (startLine < 1 || endLine > lines.size()) {
            throw new IllegalArgumentException("Line range must be between 1 and " + lines.size());
        }
    }

    private int requiredLine(JsonNode arguments, String name) {
        JsonNode node = arguments.path(name);
        if (node.isMissingNode() || node.isNull() || !node.canConvertToInt()) {
            throw new IllegalArgumentException("Missing required integer argument: " + name);
        }
        return node.asInt();
    }

    private String optionalString(JsonNode arguments, String name) {
        JsonNode node = arguments.path(name);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private boolean has(JsonNode arguments, String name) {
        JsonNode node = arguments.path(name);
        return !node.isMissingNode() && !node.isNull();
    }

    private int commonPrefixLines(List<String> oldLines, List<String> newLines) {
        int limit = Math.min(oldLines.size(), newLines.size());
        int index = 0;
        while (index < limit && oldLines.get(index).equals(newLines.get(index))) {
            index++;
        }
        return index;
    }

    private int commonSuffixLines(List<String> oldLines, List<String> newLines, int prefix) {
        int oldIndex = oldLines.size() - 1;
        int newIndex = newLines.size() - 1;
        int count = 0;
        while (oldIndex >= prefix
                && newIndex >= prefix
                && oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
            count++;
            oldIndex--;
            newIndex--;
        }
        return count;
    }

    private static class DiffSummary {
        private final int additions;
        private final int deletions;
        private final ArrayNode hunks;

        private DiffSummary(int additions, int deletions, ArrayNode hunks) {
            this.additions = additions;
            this.deletions = deletions;
            this.hunks = hunks;
        }
    }
}

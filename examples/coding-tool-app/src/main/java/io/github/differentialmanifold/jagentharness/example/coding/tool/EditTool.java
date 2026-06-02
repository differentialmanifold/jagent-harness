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
        return "Replace exact text in a UTF-8 file inside the workspace.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(objectMapper, "Workspace-relative file path."));
        properties.set("search", ToolSchemas.stringProperty(objectMapper, "Exact text to replace."));
        properties.set("replacement", ToolSchemas.stringProperty(objectMapper, "Replacement text."));
        properties.set("all", ToolSchemas.booleanProperty(objectMapper, "Replace every occurrence. Default false."));
        return ToolSchemas.objectSchema(objectMapper, properties, "path", "search", "replacement");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        Path path = pathResolver.resolve(context, ToolArguments.requiredText(arguments, "path"));
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found: " + pathResolver.relative(context, path));
        }
        String search = ToolArguments.requiredText(arguments, "search");
        String replacement = ToolArguments.requiredString(arguments, "replacement");
        boolean all = arguments.path("all").asBoolean(false);

        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        if (!content.contains(search)) {
            throw new IllegalArgumentException("Search text not found in " + pathResolver.relative(context, path));
        }
        List<ReplacementRegion> regions = new ArrayList<ReplacementRegion>();
        String updated = replaceText(content, search, replacement, all, regions);
        Files.write(path, updated.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);

        boolean changed = !content.equals(updated);
        DiffSummary diff = changed
                ? buildDiff(content, updated, replacement, regions)
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

    private String replaceText(String content,
                               String search,
                               String replacement,
                               boolean all,
                               List<ReplacementRegion> regions) {
        StringBuilder builder = new StringBuilder(content.length() + replacement.length());
        int cursor = 0;
        while (cursor <= content.length()) {
            int index = content.indexOf(search, cursor);
            if (index < 0) {
                builder.append(content.substring(cursor));
                break;
            }

            builder.append(content, cursor, index);
            int newStartOffset = builder.length();
            builder.append(replacement);
            regions.add(new ReplacementRegion(index, index + search.length(), newStartOffset));

            cursor = index + search.length();
            if (!all) {
                builder.append(content.substring(cursor));
                break;
            }
        }
        return builder.toString();
    }

    private DiffSummary buildDiff(String before,
                                  String after,
                                  String replacement,
                                  List<ReplacementRegion> regions) {
        List<String> oldLines = splitLines(before);
        List<String> newLines = splitLines(after);
        int addedLineCount = splitLines(replacement).size();
        ArrayNode hunks = objectMapper.createArrayNode();
        int additions = 0;
        int deletions = 0;

        for (ReplacementRegion region : regions) {
            int oldStartLine = lineAtOffset(before, region.oldStartOffset);
            int removedLineCount = Math.max(1, lineAtOffset(before, region.oldEndOffset - 1) - oldStartLine + 1);
            int newStartLine = lineAtOffset(after, region.newStartOffset);

            int beforeContext = Math.min(DIFF_CONTEXT_LINES, Math.min(oldStartLine, newStartLine));
            int oldAfterStart = oldStartLine + removedLineCount;
            int newAfterStart = newStartLine + addedLineCount;
            int afterContext = Math.min(DIFF_CONTEXT_LINES,
                    Math.min(Math.max(0, oldLines.size() - oldAfterStart),
                            Math.max(0, newLines.size() - newAfterStart)));

            ObjectNode hunk = objectMapper.createObjectNode();
            hunk.put("oldStart", Math.max(1, oldStartLine - beforeContext + 1));
            hunk.put("oldLines", beforeContext + removedLineCount + afterContext);
            hunk.put("newStart", Math.max(1, newStartLine - beforeContext + 1));
            hunk.put("newLines", beforeContext + addedLineCount + afterContext);

            ArrayNode lines = objectMapper.createArrayNode();
            for (int i = 0; i < beforeContext; i++) {
                int oldIndex = oldStartLine - beforeContext + i;
                int newIndex = newStartLine - beforeContext + i;
                appendDiffLine(lines, "context", oldIndex + 1, newIndex + 1, oldLines.get(oldIndex));
            }
            for (int i = 0; i < removedLineCount && oldStartLine + i < oldLines.size(); i++) {
                appendDiffLine(lines, "removed", oldStartLine + i + 1, null, oldLines.get(oldStartLine + i));
                deletions++;
            }
            for (int i = 0; i < addedLineCount && newStartLine + i < newLines.size(); i++) {
                appendDiffLine(lines, "added", null, newStartLine + i + 1, newLines.get(newStartLine + i));
                additions++;
            }
            for (int i = 0; i < afterContext; i++) {
                int oldIndex = oldAfterStart + i;
                int newIndex = newAfterStart + i;
                appendDiffLine(lines, "context", oldIndex + 1, newIndex + 1, oldLines.get(oldIndex));
            }

            hunk.set("lines", lines);
            hunks.add(hunk);
        }

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
        String[] parts = value.split("\\r?\\n", -1);
        int count = parts.length;
        if (value.endsWith("\n")) {
            count--;
        }
        for (int i = 0; i < count; i++) {
            lines.add(parts[i]);
        }
        return lines;
    }

    private int lineAtOffset(String content, int offset) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int bounded = Math.max(0, Math.min(offset, content.length() - 1));
        int line = 0;
        for (int i = 0; i < bounded; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static class ReplacementRegion {
        private final int oldStartOffset;
        private final int oldEndOffset;
        private final int newStartOffset;

        private ReplacementRegion(int oldStartOffset, int oldEndOffset, int newStartOffset) {
            this.oldStartOffset = oldStartOffset;
            this.oldEndOffset = oldEndOffset;
            this.newStartOffset = newStartOffset;
        }
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

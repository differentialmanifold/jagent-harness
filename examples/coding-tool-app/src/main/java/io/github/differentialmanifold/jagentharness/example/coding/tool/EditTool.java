package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.ContentHashing;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class EditTool implements ToolDefinition {

    private static final int DIFF_CONTEXT_LINES = 3;
    private static final int MAX_SEARCH_CANDIDATES = 3;
    private static final int SEARCH_CANDIDATE_CONTEXT_LINES = 2;
    private static final int MAX_SEARCH_CANDIDATE_LENGTH = 1200;

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
        return "Modify an existing UTF-8 file. Read the file first and pass its contentHash as expectedHash to prevent stale edits. Relative paths resolve from the workspace; absolute paths are allowed. Supports exact replacement, line-range replacement or deletion, and insertion before or after a line. Prefer this over write for localized changes.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(objectMapper, "Workspace-relative or absolute file path."));
        properties.set("expectedHash", ToolSchemas.stringProperty(objectMapper, "SHA-256 contentHash returned by read. The edit fails safely if the file changed after it was read."));
        properties.set("search", ToolSchemas.stringProperty(objectMapper, "Exact text to replace. Use with replacement for exact replacement mode."));
        properties.set("replacement", ToolSchemas.stringProperty(objectMapper, "Replacement or inserted text. Use an empty string to delete a line range."));
        properties.set("all", ToolSchemas.booleanProperty(objectMapper, "Replace every occurrence. Default false."));
        properties.set("occurrence", ToolSchemas.integerProperty(objectMapper, "One-based occurrence to replace when search matches more than once. Omit when the match is unique or all is true."));
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
        String expectedHash = normalizedExpectedHash(arguments);
        requireApprovalIfOutsideWorkspace(context, path);
        byte[] contentBytes = Files.readAllBytes(path);
        String previousHash = ContentHashing.sha256(contentBytes);
        if (expectedHash != null && !previousHash.equalsIgnoreCase(expectedHash)) {
            return editFailure(
                    context,
                    path,
                    "FILE_CHANGED",
                    "The file changed after it was read.",
                    previousHash,
                    expectedHash,
                    null,
                    null,
                    "Read the current file content and retry with its new contentHash.");
        }

        String content = new String(contentBytes, StandardCharsets.UTF_8);
        String updated;
        try {
            updated = applyEdit(context, path, arguments, content);
        } catch (EditFailureException e) {
            return editFailure(
                    context,
                    path,
                    e.code,
                    e.getMessage(),
                    previousHash,
                    expectedHash,
                    e.matchCount,
                    e.candidates,
                    e.retry);
        }
        byte[] updatedBytes = updated.getBytes(StandardCharsets.UTF_8);
        Files.write(path, updatedBytes, StandardOpenOption.TRUNCATE_EXISTING);

        boolean changed = !content.equals(updated);
        DiffSummary diff = changed
                ? buildDiff(content, updated)
                : new DiffSummary(0, 0, objectMapper.createArrayNode());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", pathResolver.relative(context, path));
        result.put("fileName", path.getFileName().toString());
        result.put("previousHash", previousHash);
        result.put("contentHash", ContentHashing.sha256(updatedBytes));
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
            boolean all = arguments.path("all").asBoolean(false);
            Integer occurrence = optionalOccurrence(arguments);
            if (all && occurrence != null) {
                throw new IllegalArgumentException("occurrence cannot be used when all is true");
            }
            SearchMatch searchMatch = findSearchMatch(content, search);
            if (searchMatch.matchCount == 0) {
                throw new EditFailureException(
                        "SEARCH_NOT_FOUND",
                        "Exact search text was not found in " + pathResolver.relative(context, path) + ".",
                        0,
                        findSearchCandidates(content, search),
                        "Read the current file content and retry with exact text from the file.");
            }
            if (occurrence != null && occurrence > searchMatch.matchCount) {
                throw new EditFailureException(
                        "OCCURRENCE_OUT_OF_RANGE",
                        "Requested occurrence " + occurrence + " but search matched "
                                + searchMatch.matchCount + " times.",
                        searchMatch.matchCount,
                        findSearchCandidates(content, search),
                        "Use an occurrence between 1 and " + searchMatch.matchCount + ", or provide a more specific search.");
            }
            if (!all && occurrence == null && searchMatch.matchCount > 1) {
                throw new EditFailureException(
                        "AMBIGUOUS_MATCH",
                        "Search text matched " + searchMatch.matchCount + " times in "
                                + pathResolver.relative(context, path) + ".",
                        searchMatch.matchCount,
                        findSearchCandidates(content, search),
                        "Provide more surrounding context, set occurrence, or set all to true.");
            }
            return replaceText(searchMatch, replacement, all, occurrence);
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

    private String replaceText(SearchMatch searchMatch,
                               String replacement,
                               boolean all,
                               Integer occurrence) {
        String effectiveReplacement = searchMatch.normalizedLineEndings
                ? normalizeLineEndings(replacement, "\n")
                : normalizeLineEndings(replacement, searchMatch.lineSeparator);
        String updated = replaceTextExact(
                searchMatch.content,
                searchMatch.search,
                effectiveReplacement,
                all,
                occurrence == null ? 1 : occurrence);
        if (searchMatch.normalizedLineEndings) {
            return normalizeLineEndings(updated, searchMatch.lineSeparator);
        }
        return updated;
    }

    private SearchMatch findSearchMatch(String content, String search) {
        String lineSeparator = detectLineSeparator(content);
        if (content.contains(search)) {
            return new SearchMatch(
                    content,
                    search,
                    lineSeparator,
                    false,
                    countOccurrences(content, search));
        }
        String logicalContent = normalizeLineEndings(content, "\n");
        String logicalSearch = normalizeLineEndings(search, "\n");
        return new SearchMatch(
                logicalContent,
                logicalSearch,
                lineSeparator,
                true,
                countOccurrences(logicalContent, logicalSearch));
    }

    private String replaceTextExact(String content,
                                    String search,
                                    String replacement,
                                    boolean all,
                                    int occurrence) {
        StringBuilder builder = new StringBuilder(content.length() + replacement.length());
        int cursor = 0;
        int matchNumber = 0;
        while (cursor <= content.length()) {
            int index = content.indexOf(search, cursor);
            if (index < 0) {
                builder.append(content.substring(cursor));
                break;
            }

            builder.append(content, cursor, index);
            matchNumber++;
            boolean replace = all || matchNumber == occurrence;
            builder.append(replace ? replacement : search);

            cursor = index + search.length();
            if (replace && !all) {
                builder.append(content.substring(cursor));
                break;
            }
        }
        return builder.toString();
    }

    private int countOccurrences(String content, String search) {
        int count = 0;
        int cursor = 0;
        while (cursor <= content.length() - search.length()) {
            int index = content.indexOf(search, cursor);
            if (index < 0) {
                break;
            }
            count++;
            cursor = index + search.length();
        }
        return count;
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

    private ToolExecutionResult editFailure(ToolContext context,
                                            Path path,
                                            String code,
                                            String message,
                                            String currentHash,
                                            String expectedHash,
                                            Integer matchCount,
                                            ArrayNode candidates,
                                            String retry) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("error", message);
        result.put("code", code);
        result.put("path", pathResolver.relative(context, path));
        result.put("contentHash", currentHash);
        if (expectedHash != null) {
            result.put("expectedHash", expectedHash);
        }
        if (matchCount != null) {
            result.put("matchCount", matchCount);
        }
        if (candidates != null && candidates.size() > 0) {
            result.set("candidates", candidates);
        }
        result.put("retry", retry);
        return ToolExecutionResult.of(result.toString());
    }

    private String normalizedExpectedHash(JsonNode arguments) {
        String value = optionalString(arguments, "expectedHash");
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String hash = value.trim();
        if (!hash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("expectedHash must be a 64-character SHA-256 value");
        }
        return hash.toLowerCase(Locale.ROOT);
    }

    private Integer optionalOccurrence(JsonNode arguments) {
        JsonNode node = arguments.path("occurrence");
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.canConvertToInt() || node.asInt() < 1) {
            throw new IllegalArgumentException("occurrence must be a positive integer");
        }
        return node.asInt();
    }

    private ArrayNode findSearchCandidates(String content, String search) {
        ArrayNode candidates = objectMapper.createArrayNode();
        List<String> contentLines = splitLines(content);
        List<String> searchLines = splitLines(search);
        if (contentLines.isEmpty() || searchLines.isEmpty()) {
            return candidates;
        }

        List<SearchCandidate> ranked = new ArrayList<SearchCandidate>();
        for (int index = 0; index < contentLines.size(); index++) {
            int score = candidateScore(contentLines.get(index), searchLines);
            if (score > 0) {
                ranked.add(new SearchCandidate(index, score));
            }
        }
        Collections.sort(ranked, new Comparator<SearchCandidate>() {
            @Override
            public int compare(SearchCandidate left, SearchCandidate right) {
                if (left.score != right.score) {
                    return right.score - left.score;
                }
                return left.lineIndex - right.lineIndex;
            }
        });

        List<Integer> selectedLines = new ArrayList<Integer>();
        for (SearchCandidate candidate : ranked) {
            if (candidates.size() >= MAX_SEARCH_CANDIDATES) {
                break;
            }
            if (nearSelectedCandidate(candidate.lineIndex, selectedLines, searchLines.size())) {
                continue;
            }
            selectedLines.add(candidate.lineIndex);
            int start = Math.max(0, candidate.lineIndex - SEARCH_CANDIDATE_CONTEXT_LINES);
            int end = Math.min(
                    contentLines.size(),
                    candidate.lineIndex + Math.max(1, searchLines.size()) + SEARCH_CANDIDATE_CONTEXT_LINES);
            String snippet = String.join("\n", contentLines.subList(start, end));
            if (snippet.length() > MAX_SEARCH_CANDIDATE_LENGTH) {
                snippet = snippet.substring(0, MAX_SEARCH_CANDIDATE_LENGTH) + "...";
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("startLine", start + 1);
            node.put("endLine", end);
            node.put("content", snippet);
            candidates.add(node);
        }
        return candidates;
    }

    private int candidateScore(String contentLine, List<String> searchLines) {
        String actual = contentLine == null ? "" : contentLine.trim();
        if (actual.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String searchLine : searchLines) {
            String expected = searchLine == null ? "" : searchLine.trim();
            if (expected.isEmpty()) {
                continue;
            }
            if (actual.equals(expected)) {
                score = Math.max(score, 1000 + Math.min(actual.length(), 200));
            } else if (actual.equalsIgnoreCase(expected)) {
                score = Math.max(score, 900 + Math.min(actual.length(), 200));
            } else {
                String actualLower = actual.toLowerCase(Locale.ROOT);
                String expectedLower = expected.toLowerCase(Locale.ROOT);
                if (actualLower.contains(expectedLower) || expectedLower.contains(actualLower)) {
                    score = Math.max(score, 700 + Math.min(actual.length(), expected.length()));
                } else {
                    int prefix = commonPrefixLength(actualLower, expectedLower);
                    int minimumLength = Math.min(actual.length(), expected.length());
                    if (prefix >= 8 && prefix * 2 >= minimumLength) {
                        score = Math.max(score, 500 + prefix);
                    }
                }
            }
        }
        return score;
    }

    private int commonPrefixLength(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        int index = 0;
        while (index < limit && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }

    private boolean nearSelectedCandidate(int lineIndex, List<Integer> selectedLines, int searchLineCount) {
        int distance = Math.max(1, searchLineCount);
        for (Integer selectedLine : selectedLines) {
            if (Math.abs(lineIndex - selectedLine) < distance) {
                return true;
            }
        }
        return false;
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

    private static class SearchMatch {
        private final String content;
        private final String search;
        private final String lineSeparator;
        private final boolean normalizedLineEndings;
        private final int matchCount;

        private SearchMatch(String content,
                            String search,
                            String lineSeparator,
                            boolean normalizedLineEndings,
                            int matchCount) {
            this.content = content;
            this.search = search;
            this.lineSeparator = lineSeparator;
            this.normalizedLineEndings = normalizedLineEndings;
            this.matchCount = matchCount;
        }
    }

    private static class SearchCandidate {
        private final int lineIndex;
        private final int score;

        private SearchCandidate(int lineIndex, int score) {
            this.lineIndex = lineIndex;
            this.score = score;
        }
    }

    private static class EditFailureException extends RuntimeException {
        private final String code;
        private final Integer matchCount;
        private final ArrayNode candidates;
        private final String retry;

        private EditFailureException(String code,
                                     String message,
                                     Integer matchCount,
                                     ArrayNode candidates,
                                     String retry) {
            super(message);
            this.code = code;
            this.matchCount = matchCount;
            this.candidates = candidates;
            this.retry = retry;
        }
    }
}

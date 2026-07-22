package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.ContentHashing;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.FileMutationCoordinator;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.Utf8Text;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class EditTool implements ToolDefinition {

    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    private static final int MAX_EDITS = 100;
    private static final int DIFF_CONTEXT_LINES = 3;
    private static final int MAX_SEARCH_CANDIDATES = 3;
    private static final int SEARCH_CANDIDATE_CONTEXT_LINES = 2;
    private static final int MAX_SEARCH_CANDIDATE_LENGTH = 1200;
    private static final long MAX_DIFF_TRACE_INTS = 8_000_000L;
    private static final char DIFF_LINE_TERMINATOR = '\0';
    private static final Set<String> TOP_LEVEL_FIELDS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("path", "edits")));
    private static final Set<String> EDIT_FIELDS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("oldText", "newText")));

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
        return "Modify one or more locations in an existing UTF-8 file. Read the file first, then provide one edits array whose oldText values each identify exactly one location in the same current file snapshot. All edits are validated together and written once.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode editProperties = objectMapper.createObjectNode();
        editProperties.set("oldText", ToolSchemas.stringProperty(
                objectMapper,
                "Text from the current file that uniquely identifies the location to replace."));
        editProperties.set("newText", ToolSchemas.stringProperty(
                objectMapper,
                "Replacement text. Use an empty string to delete oldText."));
        ObjectNode editItem = ToolSchemas.objectSchema(objectMapper, editProperties, "oldText", "newText");

        ObjectNode edits = objectMapper.createObjectNode();
        edits.put("type", "array");
        edits.put("description", "All non-overlapping replacements for this file, matched against one original snapshot.");
        edits.put("minItems", 1);
        edits.put("maxItems", MAX_EDITS);
        edits.set("items", editItem);

        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(objectMapper, "Workspace-relative or absolute file path."));
        properties.set("edits", edits);
        return ToolSchemas.objectSchema(objectMapper, properties, "path", "edits");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        context.getStopSignal().throwIfAborted();
        requireAllowedFields(arguments, TOP_LEVEL_FIELDS, "edit arguments");
        String requestedPath = requiredText(arguments, "path", "edit arguments");
        List<EditSpec> edits = parseEdits(arguments);

        Path path = pathResolver.resolve(context, requestedPath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found: " + pathResolver.relative(context, path));
        }
        Path approvedCanonicalPath = path.toRealPath();
        requireApprovalIfOutsideWorkspace(context, path, approvedCanonicalPath);

        try (FileMutationCoordinator.LockHandle lock = FileMutationCoordinator.acquire(path)) {
            context.getStopSignal().throwIfAborted();
            Path canonicalPath = lock.getCanonicalPath();
            if (!canonicalPath.equals(approvedCanonicalPath)) {
                requireApprovalIfOutsideWorkspace(context, path, canonicalPath);
            }
            if (!Files.isRegularFile(canonicalPath)) {
                throw new IllegalArgumentException("File not found: " + pathResolver.relative(context, path));
            }
            return executeLocked(context, path, canonicalPath, edits);
        }
    }

    private ToolExecutionResult executeLocked(ToolContext context,
                                              Path displayPath,
                                              Path canonicalPath,
                                              List<EditSpec> edits) throws Exception {
        TextSnapshot snapshot = decodeSnapshot(readFile(canonicalPath), canonicalPath);
        String initialHash = ContentHashing.sha256(snapshot.bytes);
        boolean rebased = false;

        for (int attempt = 0; attempt < 2; attempt++) {
            context.getStopSignal().throwIfAborted();
            EditPlan plan;
            try {
                plan = planEdits(context, displayPath, snapshot.text, edits);
            } catch (EditFailureException e) {
                if (rebased) {
                    return concurrentModificationFailure(
                            context,
                            displayPath,
                            snapshot,
                            e,
                            "The file changed while the edit was running and the replacements no longer apply safely.");
                }
                return editFailure(context, displayPath, snapshot, e);
            }

            context.getStopSignal().throwIfAborted();
            byte[] currentBytes = readFile(canonicalPath);
            if (!Arrays.equals(snapshot.bytes, currentBytes)) {
                if (attempt == 0) {
                    snapshot = decodeSnapshot(currentBytes, canonicalPath);
                    rebased = true;
                    continue;
                }
                return concurrentModificationFailure(
                        context,
                        displayPath,
                        decodeSnapshot(currentBytes, canonicalPath),
                        null,
                        "The file changed repeatedly while the edit was running.");
            }

            byte[] updatedBytes = encodeSnapshot(plan.updated, snapshot.hasBom);
            boolean changed = !Arrays.equals(snapshot.bytes, updatedBytes);
            if (changed) {
                context.getStopSignal().throwIfAborted();
                boolean committed;
                try {
                    committed = writeFileIfUnchanged(canonicalPath, snapshot.bytes, updatedBytes);
                } catch (FileMutationCoordinator.FileBusyException e) {
                    return fileBusyFailure(context, displayPath, snapshot, e);
                } catch (FileMutationCoordinator.HardLinkException e) {
                    return hardLinkFailure(context, displayPath, snapshot, e);
                } catch (FileMutationCoordinator.PathChangedException e) {
                    return pathChangedFailure(context, displayPath, snapshot, e);
                }
                if (!committed) {
                    byte[] changedBytes = readFile(canonicalPath);
                    if (attempt == 0) {
                        snapshot = decodeSnapshot(changedBytes, canonicalPath);
                        rebased = true;
                        continue;
                    }
                    return concurrentModificationFailure(
                            context,
                            displayPath,
                            decodeSnapshot(changedBytes, canonicalPath),
                            null,
                            "The file changed repeatedly while the edit was being committed.");
                }
            }
            return successResult(
                    context,
                    displayPath,
                    initialHash,
                    snapshot,
                    updatedBytes,
                    plan,
                    changed,
                    rebased);
        }

        throw new IllegalStateException("Edit retry loop completed unexpectedly.");
    }

    private List<EditSpec> parseEdits(JsonNode arguments) {
        JsonNode editsNode = arguments.path("edits");
        if (!editsNode.isArray()) {
            throw new IllegalArgumentException("edits must be a non-empty array");
        }
        if (editsNode.size() == 0) {
            throw new IllegalArgumentException("edits must contain at least one replacement");
        }
        if (editsNode.size() > MAX_EDITS) {
            throw new IllegalArgumentException("edits must contain at most " + MAX_EDITS + " replacements");
        }

        List<EditSpec> edits = new ArrayList<EditSpec>();
        for (int index = 0; index < editsNode.size(); index++) {
            JsonNode edit = editsNode.get(index);
            requireAllowedFields(edit, EDIT_FIELDS, "edits[" + index + "]");
            String oldText = requiredString(edit, "oldText", "edits[" + index + "]");
            String newText = requiredString(edit, "newText", "edits[" + index + "]");
            if (oldText.isEmpty()) {
                throw new IllegalArgumentException("edits[" + index + "].oldText must not be empty");
            }
            if (oldText.equals(newText)) {
                throw new IllegalArgumentException(
                        "edits[" + index + "].oldText and newText must be different");
            }
            Utf8Text.validate(oldText, "edits[" + index + "].oldText");
            Utf8Text.validate(newText, "edits[" + index + "].newText");
            edits.add(new EditSpec(index, oldText, newText));
        }
        return edits;
    }

    private EditPlan planEdits(ToolContext context,
                               Path path,
                               String content,
                               List<EditSpec> edits) {
        List<PlannedEdit> planned = new ArrayList<PlannedEdit>();
        String defaultLineSeparator = detectLineSeparator(content, "\n");
        MatchingDocument document = new MatchingDocument(
                content,
                normalizeView(content, false),
                normalizeView(content, true),
                logicalLines(content));

        for (EditSpec edit : edits) {
            context.getStopSignal().throwIfAborted();
            MatchResult result = findMatches(document, edit.oldText);
            if (result.spans.isEmpty()) {
                throw new EditFailureException(
                        "SEARCH_NOT_FOUND",
                        "oldText for edit " + edit.index + " was not found in "
                                + pathResolver.relative(context, path) + ".",
                        edit.index,
                        0,
                        findSearchCandidates(content, edit.oldText),
                        "Read the current file and retry with oldText copied from its latest content.",
                        null,
                        null);
            }
            if (result.spans.size() > 1) {
                throw new EditFailureException(
                        "AMBIGUOUS_MATCH",
                        "oldText for edit " + edit.index + " matched " + result.spans.size()
                                + " locations in " + pathResolver.relative(context, path) + ".",
                        edit.index,
                        result.spans.size(),
                        actualMatchCandidates(content, result.spans),
                        "Add more surrounding context to oldText so it identifies exactly one location.",
                        result.strategy,
                        null);
            }

            Span span = result.spans.get(0);
            String matchedText = content.substring(span.start, span.end);
            String replacementLineSeparator = detectLineSeparatorNear(
                    content,
                    span,
                    defaultLineSeparator);
            String replacement = normalizeLineEndings(edit.newText, replacementLineSeparator);
            if ("common_indentation".equals(result.strategy) && !replacement.isEmpty()) {
                replacement = reindentReplacement(replacement, matchedText, replacementLineSeparator);
            }
            planned.add(new PlannedEdit(
                    edit.index,
                    span.start,
                    span.end,
                    replacement,
                    result.strategy,
                    lineNumberAt(content, span.start),
                    lineNumberAt(content, Math.max(span.start, span.end - 1))));
        }

        Collections.sort(planned, new Comparator<PlannedEdit>() {
            @Override
            public int compare(PlannedEdit left, PlannedEdit right) {
                if (left.start != right.start) {
                    return left.start - right.start;
                }
                return left.end - right.end;
            }
        });
        for (int index = 1; index < planned.size(); index++) {
            PlannedEdit previous = planned.get(index - 1);
            PlannedEdit current = planned.get(index);
            if (current.start < previous.end) {
                throw new EditFailureException(
                        "OVERLAPPING_EDITS",
                        "Edits " + previous.index + " and " + current.index
                                + " target overlapping text ranges.",
                        current.index,
                        null,
                        null,
                        "Merge overlapping replacements into one edit entry.",
                        null,
                        Arrays.asList(previous.index, current.index));
            }
        }

        StringBuilder updated = new StringBuilder(content);
        for (int index = planned.size() - 1; index >= 0; index--) {
            PlannedEdit edit = planned.get(index);
            updated.replace(edit.start, edit.end, edit.replacement);
        }
        return new EditPlan(content, updated.toString(), planned);
    }

    private MatchResult findMatches(MatchingDocument document, String oldText) {
        String normalizedOldText = normalizeText(oldText, false);
        List<Span> logicalMatches = findSpans(document.lineEndings, normalizedOldText);
        if (!logicalMatches.isEmpty()) {
            String strategy = "line_endings";
            if (logicalMatches.size() == 1) {
                Span span = logicalMatches.get(0);
                if (document.content.substring(span.start, span.end).equals(oldText)) {
                    strategy = "exact";
                }
            }
            return new MatchResult(strategy, logicalMatches);
        }

        String trailingWhitespaceOldText = normalizeText(oldText, true);
        if (!trailingWhitespaceOldText.isEmpty()) {
            List<Span> trailingWhitespaceMatches = findSpans(
                    document.trailingWhitespace,
                    trailingWhitespaceOldText);
            if (!trailingWhitespaceMatches.isEmpty()) {
                return new MatchResult("trailing_whitespace", trailingWhitespaceMatches);
            }
        }

        List<Span> indentationMatches = findIndentationMatches(document, oldText);
        return new MatchResult("common_indentation", indentationMatches);
    }

    private List<Span> findSpans(NormalizedView view, String search) {
        List<Span> spans = new ArrayList<Span>();
        if (search.isEmpty() || search.length() > view.text.length()) {
            return spans;
        }
        int cursor = 0;
        while (cursor <= view.text.length() - search.length()) {
            int index = view.text.indexOf(search, cursor);
            if (index < 0) {
                break;
            }
            Span span = new Span(
                    view.originalStarts[index],
                    view.originalEnds[index + search.length() - 1]);
            if (spans.isEmpty() || !spans.get(spans.size() - 1).equals(span)) {
                spans.add(span);
            }
            cursor = index + 1;
        }
        return spans;
    }

    private NormalizedView normalizeView(String value, boolean trimTrailingWhitespace) {
        StringBuilder normalized = new StringBuilder(value.length());
        int[] starts = new int[value.length()];
        int[] ends = new int[value.length()];
        int normalizedLength = 0;
        int cursor = 0;
        while (cursor < value.length()) {
            int separatorStart = cursor;
            while (separatorStart < value.length()
                    && value.charAt(separatorStart) != '\r'
                    && value.charAt(separatorStart) != '\n') {
                separatorStart++;
            }
            int keptEnd = separatorStart;
            if (trimTrailingWhitespace && separatorStart < value.length()) {
                while (keptEnd > cursor) {
                    char trailing = value.charAt(keptEnd - 1);
                    if (trailing != ' ' && trailing != '\t') {
                        break;
                    }
                    keptEnd--;
                }
            }
            for (int index = cursor; index < keptEnd; index++) {
                normalized.append(value.charAt(index));
                starts[normalizedLength] = index;
                ends[normalizedLength] = index + 1;
                normalizedLength++;
            }
            if (separatorStart >= value.length()) {
                break;
            }
            int separatorEnd = separatorStart + 1;
            if (value.charAt(separatorStart) == '\r'
                    && separatorEnd < value.length()
                    && value.charAt(separatorEnd) == '\n') {
                separatorEnd++;
            }
            normalized.append('\n');
            starts[normalizedLength] = separatorStart;
            ends[normalizedLength] = separatorEnd;
            normalizedLength++;
            cursor = separatorEnd;
        }
        return new NormalizedView(
                normalized.toString(),
                Arrays.copyOf(starts, normalizedLength),
                Arrays.copyOf(ends, normalizedLength));
    }

    private String normalizeText(String value, boolean trimTrailingWhitespace) {
        StringBuilder normalized = new StringBuilder(value.length());
        int cursor = 0;
        while (cursor < value.length()) {
            int separatorStart = cursor;
            while (separatorStart < value.length()
                    && value.charAt(separatorStart) != '\r'
                    && value.charAt(separatorStart) != '\n') {
                separatorStart++;
            }
            int keptEnd = separatorStart;
            if (trimTrailingWhitespace && separatorStart < value.length()) {
                while (keptEnd > cursor) {
                    char trailing = value.charAt(keptEnd - 1);
                    if (trailing != ' ' && trailing != '\t') {
                        break;
                    }
                    keptEnd--;
                }
            }
            normalized.append(value, cursor, keptEnd);
            if (separatorStart >= value.length()) {
                break;
            }
            int separatorEnd = separatorStart + 1;
            if (value.charAt(separatorStart) == '\r'
                    && separatorEnd < value.length()
                    && value.charAt(separatorEnd) == '\n') {
                separatorEnd++;
            }
            normalized.append('\n');
            cursor = separatorEnd;
        }
        return normalized.toString();
    }

    private List<Span> findIndentationMatches(MatchingDocument document, String oldText) {
        String content = document.content;
        String normalizedOldText = normalizeLineEndings(oldText, "\n");
        boolean includesTrailingLineSeparator = normalizedOldText.endsWith("\n");
        String[] oldLines = normalizedOldText.split("\n", -1);
        int lineCount = oldLines.length - (includesTrailingLineSeparator ? 1 : 0);
        if (lineCount < 1) {
            return Collections.emptyList();
        }

        String expected = normalizeCommonIndent(oldText);
        if (expected.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<LogicalLine> lines = document.lines;
        List<Span> matches = new ArrayList<Span>();
        for (int startLine = 0; startLine + lineCount <= lines.size(); startLine++) {
            LogicalLine first = lines.get(startLine);
            LogicalLine last = lines.get(startLine + lineCount - 1);
            if (includesTrailingLineSeparator && last.separatorEnd == last.contentEnd) {
                continue;
            }
            int end = includesTrailingLineSeparator ? last.separatorEnd : last.contentEnd;
            String actual = content.substring(first.start, end);
            if (normalizeCommonIndent(actual).equals(expected)) {
                matches.add(new Span(first.start, end));
            }
        }
        return matches;
    }

    private String normalizeCommonIndent(String value) {
        String normalized = normalizeText(value, true);
        String[] lines = normalized.split("\n", -1);
        int effectiveLength = lines.length;
        if (effectiveLength > 0 && lines[effectiveLength - 1].isEmpty() && normalized.endsWith("\n")) {
            effectiveLength--;
        }
        int minimumIndent = Integer.MAX_VALUE;
        for (int index = 0; index < effectiveLength; index++) {
            if (lines[index].trim().isEmpty()) {
                continue;
            }
            minimumIndent = Math.min(minimumIndent, leadingIndent(lines[index]));
        }
        if (minimumIndent == Integer.MAX_VALUE) {
            minimumIndent = 0;
        }

        StringBuilder result = new StringBuilder(normalized.length());
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            int remove = Math.min(minimumIndent, leadingIndent(line));
            result.append(line.substring(remove));
            if (index + 1 < lines.length) {
                result.append('\n');
            }
        }
        return result.toString();
    }

    private int leadingIndent(String value) {
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current != ' ' && current != '\t') {
                break;
            }
            index++;
        }
        return index;
    }

    private String reindentReplacement(String replacement,
                                       String matchedText,
                                       String lineSeparator) {
        String logicalReplacement = normalizeLineEndings(replacement, "\n");
        String[] lines = logicalReplacement.split("\n", -1);
        int effectiveLength = lines.length;
        if (effectiveLength > 0
                && lines[effectiveLength - 1].isEmpty()
                && logicalReplacement.endsWith("\n")) {
            effectiveLength--;
        }
        int replacementIndent = Integer.MAX_VALUE;
        for (int index = 0; index < effectiveLength; index++) {
            if (!lines[index].trim().isEmpty()) {
                replacementIndent = Math.min(replacementIndent, leadingIndent(lines[index]));
            }
        }
        if (replacementIndent == Integer.MAX_VALUE) {
            replacementIndent = 0;
        }
        String targetIndent = commonIndentPrefix(matchedText);
        StringBuilder result = new StringBuilder(replacement.length() + targetIndent.length() * lines.length);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (!line.trim().isEmpty()) {
                int remove = Math.min(replacementIndent, leadingIndent(line));
                result.append(targetIndent).append(line.substring(remove));
            }
            if (index + 1 < lines.length) {
                result.append(lineSeparator);
            }
        }
        return result.toString();
    }

    private String commonIndentPrefix(String value) {
        String[] lines = normalizeLineEndings(value, "\n").split("\n", -1);
        String prefix = "";
        int minimumIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            int indent = leadingIndent(line);
            if (indent < minimumIndent) {
                minimumIndent = indent;
                prefix = line.substring(0, indent);
            }
        }
        return prefix;
    }

    private List<LogicalLine> logicalLines(String content) {
        List<LogicalLine> lines = new ArrayList<LogicalLine>();
        int cursor = 0;
        while (cursor < content.length()) {
            int contentEnd = cursor;
            while (contentEnd < content.length()
                    && content.charAt(contentEnd) != '\r'
                    && content.charAt(contentEnd) != '\n') {
                contentEnd++;
            }
            int separatorEnd = contentEnd;
            if (separatorEnd < content.length()) {
                separatorEnd++;
                if (content.charAt(contentEnd) == '\r'
                        && separatorEnd < content.length()
                        && content.charAt(separatorEnd) == '\n') {
                    separatorEnd++;
                }
            }
            lines.add(new LogicalLine(cursor, contentEnd, separatorEnd));
            cursor = separatorEnd;
        }
        return lines;
    }

    private ToolExecutionResult successResult(ToolContext context,
                                              Path path,
                                              String initialHash,
                                              TextSnapshot snapshot,
                                              byte[] updatedBytes,
                                              EditPlan plan,
                                              boolean changed,
                                              boolean rebased) {
        DiffSummary diff = changed
                ? buildDiff(plan.before, plan.updated)
                : new DiffSummary(0, 0, objectMapper.createArrayNode());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", pathResolver.relative(context, path));
        result.put("fileName", path.getFileName().toString());
        result.put("initialHash", initialHash);
        result.put("previousHash", ContentHashing.sha256(snapshot.bytes));
        result.put("contentHash", ContentHashing.sha256(updatedBytes));
        result.put("changed", changed);
        result.put("rebased", rebased);
        result.put("editCount", plan.edits.size());
        result.put("additions", diff.additions);
        result.put("deletions", diff.deletions);
        ArrayNode appliedEdits = result.putArray("appliedEdits");
        List<PlannedEdit> inputOrder = new ArrayList<PlannedEdit>(plan.edits);
        Collections.sort(inputOrder, new Comparator<PlannedEdit>() {
            @Override
            public int compare(PlannedEdit left, PlannedEdit right) {
                return left.index - right.index;
            }
        });
        for (PlannedEdit edit : inputOrder) {
            ObjectNode applied = appliedEdits.addObject();
            applied.put("index", edit.index);
            applied.put("matchStrategy", edit.strategy);
            applied.put("startLine", edit.startLine);
            applied.put("endLine", edit.endLine);
        }
        ObjectNode diffNode = objectMapper.createObjectNode();
        diffNode.set("hunks", diff.hunks);
        result.set("diff", diffNode);
        return ToolExecutionResult.of(result.toString());
    }

    private ToolExecutionResult editFailure(ToolContext context,
                                            Path path,
                                            TextSnapshot snapshot,
                                            EditFailureException failure) {
        ObjectNode result = baseFailure(context, path, snapshot, failure.getMessage(), failure.retry);
        result.put("code", failure.code);
        appendFailureDetails(result, failure);
        return ToolExecutionResult.of(result.toString());
    }

    private ToolExecutionResult concurrentModificationFailure(ToolContext context,
                                                              Path path,
                                                              TextSnapshot snapshot,
                                                              EditFailureException cause,
                                                              String message) {
        ObjectNode result = baseFailure(
                context,
                path,
                snapshot,
                message,
                "Read the current file and retry the complete edit batch against its latest content.");
        result.put("code", "CONCURRENT_MODIFICATION");
        if (cause != null) {
            result.put("causeCode", cause.code);
            appendFailureDetails(result, cause);
        }
        return ToolExecutionResult.of(result.toString());
    }

    private ToolExecutionResult fileBusyFailure(ToolContext context,
                                                Path path,
                                                TextSnapshot snapshot,
                                                FileMutationCoordinator.FileBusyException failure) {
        ObjectNode result = baseFailure(
                context,
                path,
                snapshot,
                failure.getMessage(),
                "Close programs that hold the file open, verify write permissions, and retry the edit.");
        result.put("code", "FILE_BUSY");
        result.put("target", failure.getTarget().toString());
        return ToolExecutionResult.of(result.toString());
    }

    private ToolExecutionResult hardLinkFailure(ToolContext context,
                                                Path path,
                                                TextSnapshot snapshot,
                                                FileMutationCoordinator.HardLinkException failure) {
        ObjectNode result = baseFailure(
                context,
                path,
                snapshot,
                failure.getMessage(),
                "Edit the canonical source file in a workflow that intentionally updates every hard link.");
        result.put("code", "HARD_LINK_UNSUPPORTED");
        result.put("target", failure.getTarget().toString());
        result.put("linkCount", failure.getLinkCount());
        return ToolExecutionResult.of(result.toString());
    }

    private ToolExecutionResult pathChangedFailure(ToolContext context,
                                                   Path path,
                                                   TextSnapshot snapshot,
                                                   FileMutationCoordinator.PathChangedException failure) {
        ObjectNode result = baseFailure(
                context,
                path,
                snapshot,
                failure.getMessage(),
                "Resolve and approve the file's current location before retrying the edit.");
        result.put("code", "PATH_CHANGED");
        result.put("approvedTarget", failure.getApprovedTarget().toString());
        result.put("currentTarget", failure.getCurrentTarget().toString());
        return ToolExecutionResult.of(result.toString());
    }

    private ObjectNode baseFailure(ToolContext context,
                                   Path path,
                                   TextSnapshot snapshot,
                                   String message,
                                   String retry) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("error", message);
        result.put("path", pathResolver.relative(context, path));
        result.put("contentHash", ContentHashing.sha256(snapshot.bytes));
        result.put("retry", retry);
        return result;
    }

    private void appendFailureDetails(ObjectNode result, EditFailureException failure) {
        if (failure.editIndex != null) {
            result.put("editIndex", failure.editIndex);
        }
        if (failure.matchCount != null) {
            result.put("matchCount", failure.matchCount);
        }
        if (failure.matchStrategy != null) {
            result.put("matchStrategy", failure.matchStrategy);
        }
        if (failure.candidates != null && failure.candidates.size() > 0) {
            result.set("candidates", failure.candidates);
        }
        if (failure.conflictingEditIndexes != null) {
            ArrayNode indexes = result.putArray("conflictingEditIndexes");
            for (Integer index : failure.conflictingEditIndexes) {
                indexes.add(index);
            }
        }
    }

    private DiffSummary buildDiff(String before, String after) {
        List<String> oldLines = splitDiffLines(before);
        List<String> newLines = splitDiffLines(after);
        List<DiffOperation> operations = myersDiff(oldLines, newLines);
        ArrayNode hunks = objectMapper.createArrayNode();
        int additions = 0;
        int deletions = 0;
        List<Integer> changes = new ArrayList<Integer>();
        int oldLine = 1;
        int newLine = 1;
        for (int index = 0; index < operations.size(); index++) {
            DiffOperation operation = operations.get(index);
            if (operation.type == DiffType.CONTEXT) {
                operation.oldLine = oldLine++;
                operation.newLine = newLine++;
            } else if (operation.type == DiffType.REMOVED) {
                operation.oldLine = oldLine++;
                deletions++;
                changes.add(index);
            } else {
                operation.newLine = newLine++;
                additions++;
                changes.add(index);
            }
        }

        int changeCursor = 0;
        while (changeCursor < changes.size()) {
            int firstChange = changes.get(changeCursor);
            int lastChange = firstChange;
            while (changeCursor + 1 < changes.size()
                    && changes.get(changeCursor + 1) <= lastChange + (DIFF_CONTEXT_LINES * 2) + 1) {
                changeCursor++;
                lastChange = changes.get(changeCursor);
            }
            int start = Math.max(0, firstChange - DIFF_CONTEXT_LINES);
            int end = Math.min(operations.size(), lastChange + DIFF_CONTEXT_LINES + 1);
            appendDiffHunk(hunks, operations, start, end);
            changeCursor++;
        }
        return new DiffSummary(additions, deletions, hunks);
    }

    private void appendDiffHunk(ArrayNode hunks,
                                List<DiffOperation> operations,
                                int start,
                                int end) {
        int oldStart = 1;
        int newStart = 1;
        for (int index = 0; index < start; index++) {
            DiffOperation operation = operations.get(index);
            if (operation.type != DiffType.ADDED) {
                oldStart++;
            }
            if (operation.type != DiffType.REMOVED) {
                newStart++;
            }
        }

        int oldCount = 0;
        int newCount = 0;
        ArrayNode lines = objectMapper.createArrayNode();
        for (int index = start; index < end; index++) {
            DiffOperation operation = operations.get(index);
            if (operation.type != DiffType.ADDED) {
                oldCount++;
            }
            if (operation.type != DiffType.REMOVED) {
                newCount++;
            }
            appendDiffLine(
                    lines,
                    operation.type.jsonName,
                    operation.oldLine,
                    operation.newLine,
                    operation.content);
        }

        ObjectNode hunk = hunks.addObject();
        hunk.put("oldStart", oldStart);
        hunk.put("oldLines", oldCount);
        hunk.put("newStart", newStart);
        hunk.put("newLines", newCount);
        hunk.set("lines", lines);
    }

    private List<DiffOperation> myersDiff(List<String> oldLines, List<String> newLines) {
        int oldSize = oldLines.size();
        int newSize = newLines.size();
        int maximum = oldSize + newSize;
        if (maximum == 0) {
            return Collections.emptyList();
        }
        int offset = maximum;
        int vectorSize = (maximum * 2) + 1;
        int[] vector = new int[vectorSize];
        vector[offset + 1] = 0;
        List<int[]> trace = new ArrayList<int[]>();

        for (int distance = 0; distance <= maximum; distance++) {
            if ((long) (trace.size() + 1) * vectorSize > MAX_DIFF_TRACE_INTS) {
                return fallbackDiff(oldLines, newLines);
            }
            trace.add(Arrays.copyOf(vector, vector.length));
            for (int diagonal = -distance; diagonal <= distance; diagonal += 2) {
                int vectorIndex = offset + diagonal;
                int oldIndex;
                if (diagonal == -distance
                        || (diagonal != distance
                        && vector[vectorIndex - 1] < vector[vectorIndex + 1])) {
                    oldIndex = vector[vectorIndex + 1];
                } else {
                    oldIndex = vector[vectorIndex - 1] + 1;
                }
                int newIndex = oldIndex - diagonal;
                while (oldIndex < oldSize
                        && newIndex < newSize
                        && oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
                    oldIndex++;
                    newIndex++;
                }
                vector[vectorIndex] = oldIndex;
                if (oldIndex >= oldSize && newIndex >= newSize) {
                    return backtrackDiff(trace, oldLines, newLines);
                }
            }
        }
        return fallbackDiff(oldLines, newLines);
    }

    private List<DiffOperation> backtrackDiff(List<int[]> trace,
                                              List<String> oldLines,
                                              List<String> newLines) {
        List<DiffOperation> reversed = new ArrayList<DiffOperation>();
        int oldIndex = oldLines.size();
        int newIndex = newLines.size();
        int offset = oldLines.size() + newLines.size();

        for (int distance = trace.size() - 1; distance >= 0; distance--) {
            int[] vector = trace.get(distance);
            int diagonal = oldIndex - newIndex;
            int previousDiagonal;
            if (diagonal == -distance
                    || (diagonal != distance
                    && vector[offset + diagonal - 1] < vector[offset + diagonal + 1])) {
                previousDiagonal = diagonal + 1;
            } else {
                previousDiagonal = diagonal - 1;
            }
            int previousOldIndex = vector[offset + previousDiagonal];
            int previousNewIndex = previousOldIndex - previousDiagonal;

            while (oldIndex > previousOldIndex && newIndex > previousNewIndex) {
                oldIndex--;
                newIndex--;
                reversed.add(new DiffOperation(DiffType.CONTEXT, oldLines.get(oldIndex)));
            }
            if (distance == 0) {
                break;
            }
            if (oldIndex == previousOldIndex) {
                newIndex--;
                reversed.add(new DiffOperation(DiffType.ADDED, newLines.get(newIndex)));
            } else {
                oldIndex--;
                reversed.add(new DiffOperation(DiffType.REMOVED, oldLines.get(oldIndex)));
            }
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private List<DiffOperation> fallbackDiff(List<String> oldLines, List<String> newLines) {
        List<DiffOperation> operations = new ArrayList<DiffOperation>();
        int prefix = commonPrefixLines(oldLines, newLines);
        int suffix = commonSuffixLines(oldLines, newLines, prefix);
        for (int index = 0; index < prefix; index++) {
            operations.add(new DiffOperation(DiffType.CONTEXT, oldLines.get(index)));
        }
        for (int index = prefix; index < oldLines.size() - suffix; index++) {
            operations.add(new DiffOperation(DiffType.REMOVED, oldLines.get(index)));
        }
        for (int index = prefix; index < newLines.size() - suffix; index++) {
            operations.add(new DiffOperation(DiffType.ADDED, newLines.get(index)));
        }
        for (int index = oldLines.size() - suffix; index < oldLines.size(); index++) {
            operations.add(new DiffOperation(DiffType.CONTEXT, oldLines.get(index)));
        }
        return operations;
    }

    private void appendDiffLine(ArrayNode lines,
                                String type,
                                Integer oldLine,
                                Integer newLine,
                                String content) {
        ObjectNode line = lines.addObject();
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
        boolean lineTerminated = !content.isEmpty()
                && content.charAt(content.length() - 1) == DIFF_LINE_TERMINATOR;
        line.put("lineTerminated", lineTerminated);
        line.put("content", lineTerminated ? content.substring(0, content.length() - 1) : content);
    }

    private ArrayNode actualMatchCandidates(String content, List<Span> spans) {
        ArrayNode candidates = objectMapper.createArrayNode();
        for (int index = 0; index < spans.size() && index < MAX_SEARCH_CANDIDATES; index++) {
            Span span = spans.get(index);
            ObjectNode candidate = candidates.addObject();
            candidate.put("startLine", lineNumberAt(content, span.start));
            candidate.put("endLine", lineNumberAt(content, Math.max(span.start, span.end - 1)));
            String matched = content.substring(span.start, span.end);
            candidate.put("content", abbreviate(matched));
        }
        return candidates;
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
            ObjectNode node = candidates.addObject();
            node.put("startLine", start + 1);
            node.put("endLine", end);
            node.put("content", abbreviate(String.join("\n", contentLines.subList(start, end))));
        }
        return candidates;
    }

    private String abbreviate(String value) {
        return value.length() <= MAX_SEARCH_CANDIDATE_LENGTH
                ? value
                : value.substring(0, MAX_SEARCH_CANDIDATE_LENGTH) + "...";
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

    private List<String> splitLines(String value) {
        List<String> lines = new ArrayList<String>();
        if (value == null || value.isEmpty()) {
            return lines;
        }
        int cursor = 0;
        while (cursor < value.length()) {
            int end = cursor;
            while (end < value.length() && value.charAt(end) != '\r' && value.charAt(end) != '\n') {
                end++;
            }
            lines.add(value.substring(cursor, end));
            if (end >= value.length()) {
                break;
            }
            if (value.charAt(end) == '\r' && end + 1 < value.length() && value.charAt(end + 1) == '\n') {
                end++;
            }
            cursor = end + 1;
        }
        return lines;
    }

    private List<String> splitDiffLines(String value) {
        List<String> lines = new ArrayList<String>();
        if (value == null || value.isEmpty()) {
            return lines;
        }
        int cursor = 0;
        while (cursor < value.length()) {
            int end = cursor;
            while (end < value.length() && value.charAt(end) != '\r' && value.charAt(end) != '\n') {
                end++;
            }
            boolean terminated = end < value.length();
            String line = value.substring(cursor, end);
            lines.add(terminated ? line + DIFF_LINE_TERMINATOR : line);
            if (!terminated) {
                break;
            }
            if (value.charAt(end) == '\r'
                    && end + 1 < value.length()
                    && value.charAt(end + 1) == '\n') {
                end++;
            }
            cursor = end + 1;
        }
        return lines;
    }

    private int lineNumberAt(String content, int offset) {
        int line = 1;
        int limit = Math.min(Math.max(offset, 0), content.length());
        for (int index = 0; index < limit; index++) {
            char current = content.charAt(index);
            if (current == '\r') {
                if (index + 1 < content.length() && content.charAt(index + 1) == '\n') {
                    if (index + 1 < limit) {
                        index++;
                        line++;
                    }
                    continue;
                }
                line++;
            } else if (current == '\n') {
                line++;
            }
        }
        return line;
    }

    private String detectLineSeparatorNear(String content, Span span, String fallback) {
        String matched = content.substring(span.start, span.end);
        String inMatch = detectLineSeparator(matched, null);
        if (inMatch != null) {
            return inMatch;
        }
        for (int index = span.end; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '\n') {
                return "\n";
            }
            if (current == '\r') {
                return index + 1 < content.length() && content.charAt(index + 1) == '\n'
                        ? "\r\n"
                        : "\r";
            }
        }
        for (int index = span.start - 1; index >= 0; index--) {
            char current = content.charAt(index);
            if (current == '\n') {
                return index > 0 && content.charAt(index - 1) == '\r' ? "\r\n" : "\n";
            }
            if (current == '\r') {
                return "\r";
            }
        }
        return fallback;
    }

    private String detectLineSeparator(String value, String fallback) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\n') {
                return "\n";
            }
            if (current == '\r') {
                return index + 1 < value.length() && value.charAt(index + 1) == '\n'
                        ? "\r\n"
                        : "\r";
            }
        }
        return fallback;
    }

    private String normalizeLineEndings(String value, String lineSeparator) {
        if (value.isEmpty()) {
            return value;
        }
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\r') {
                if (index + 1 < value.length() && value.charAt(index + 1) == '\n') {
                    index++;
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

    private TextSnapshot decodeSnapshot(byte[] bytes, Path path) {
        boolean hasBom = startsWithBom(bytes);
        int offset = hasBom ? UTF8_BOM.length : 0;
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    "Edit tool supports valid UTF-8 text files only: " + path.getFileName(),
                    e);
        }
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "Edit tool supports UTF-8 text files without NUL bytes only: " + path.getFileName());
        }
        return new TextSnapshot(bytes, text, hasBom);
    }

    private byte[] encodeSnapshot(String text, boolean hasBom) {
        byte[] content = Utf8Text.encode(text, "Edited file content");
        if (!hasBom) {
            return content;
        }
        byte[] bytes = new byte[UTF8_BOM.length + content.length];
        System.arraycopy(UTF8_BOM, 0, bytes, 0, UTF8_BOM.length);
        System.arraycopy(content, 0, bytes, UTF8_BOM.length, content.length);
        return bytes;
    }

    private boolean startsWithBom(byte[] bytes) {
        return bytes.length >= UTF8_BOM.length
                && bytes[0] == UTF8_BOM[0]
                && bytes[1] == UTF8_BOM[1]
                && bytes[2] == UTF8_BOM[2];
    }

    private void requireApprovalIfOutsideWorkspace(ToolContext context,
                                                   Path requestedPath,
                                                   Path canonicalPath) throws Exception {
        Path workspaceRoot = pathResolver.workspaceRoot(context);
        Path canonicalWorkspaceRoot = FileMutationCoordinator.canonicalPath(workspaceRoot);
        boolean requestedInsideWorkspace = requestedPath.toAbsolutePath().normalize().startsWith(workspaceRoot);
        boolean canonicalInsideWorkspace = canonicalPath.toAbsolutePath().normalize().startsWith(canonicalWorkspaceRoot);
        if (requestedInsideWorkspace && canonicalInsideWorkspace) {
            return;
        }
        context.requestApproval(new ToolApprovalRequest(
                "Approve edit outside workspace",
                "The edit tool wants to modify a file outside the current workspace.",
                "edit",
                canonicalPath.toAbsolutePath().normalize().toString()));
    }

    private void requireAllowedFields(JsonNode node, Set<String> allowed, String location) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(location + " must be an object");
        }
        java.util.Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unknown argument " + location + "." + field);
            }
        }
    }

    private String requiredText(JsonNode node, String name, String location) {
        String value = requiredString(node, name, location);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(location + "." + name + " must not be blank");
        }
        return value;
    }

    private String requiredString(JsonNode node, String name, String location) {
        JsonNode value = node.path(name);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(location + "." + name + " must be a string");
        }
        return value.asText();
    }

    byte[] readFile(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    boolean writeFileIfUnchanged(Path path, byte[] expected, byte[] bytes) throws IOException {
        return FileMutationCoordinator.writeAtomicallyIfUnchanged(path, expected, bytes);
    }

    private static final class EditSpec {
        private final int index;
        private final String oldText;
        private final String newText;

        private EditSpec(int index, String oldText, String newText) {
            this.index = index;
            this.oldText = oldText;
            this.newText = newText;
        }
    }

    private static final class EditPlan {
        private final String before;
        private final String updated;
        private final List<PlannedEdit> edits;

        private EditPlan(String before, String updated, List<PlannedEdit> edits) {
            this.before = before;
            this.updated = updated;
            this.edits = edits;
        }
    }

    private static final class PlannedEdit {
        private final int index;
        private final int start;
        private final int end;
        private final String replacement;
        private final String strategy;
        private final int startLine;
        private final int endLine;

        private PlannedEdit(int index,
                            int start,
                            int end,
                            String replacement,
                            String strategy,
                            int startLine,
                            int endLine) {
            this.index = index;
            this.start = start;
            this.end = end;
            this.replacement = replacement;
            this.strategy = strategy;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    private static final class MatchResult {
        private final String strategy;
        private final List<Span> spans;

        private MatchResult(String strategy, List<Span> spans) {
            this.strategy = strategy;
            this.spans = spans;
        }
    }

    private static final class MatchingDocument {
        private final String content;
        private final NormalizedView lineEndings;
        private final NormalizedView trailingWhitespace;
        private final List<LogicalLine> lines;

        private MatchingDocument(String content,
                                 NormalizedView lineEndings,
                                 NormalizedView trailingWhitespace,
                                 List<LogicalLine> lines) {
            this.content = content;
            this.lineEndings = lineEndings;
            this.trailingWhitespace = trailingWhitespace;
            this.lines = lines;
        }
    }

    private static final class Span {
        private final int start;
        private final int end;

        private Span(int start, int end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Span)) {
                return false;
            }
            Span span = (Span) other;
            return start == span.start && end == span.end;
        }

        @Override
        public int hashCode() {
            return 31 * start + end;
        }
    }

    private static final class NormalizedView {
        private final String text;
        private final int[] originalStarts;
        private final int[] originalEnds;

        private NormalizedView(String text, int[] originalStarts, int[] originalEnds) {
            this.text = text;
            this.originalStarts = originalStarts;
            this.originalEnds = originalEnds;
        }
    }

    private static final class LogicalLine {
        private final int start;
        private final int contentEnd;
        private final int separatorEnd;

        private LogicalLine(int start, int contentEnd, int separatorEnd) {
            this.start = start;
            this.contentEnd = contentEnd;
            this.separatorEnd = separatorEnd;
        }
    }

    private static final class TextSnapshot {
        private final byte[] bytes;
        private final String text;
        private final boolean hasBom;

        private TextSnapshot(byte[] bytes, String text, boolean hasBom) {
            this.bytes = bytes;
            this.text = text;
            this.hasBom = hasBom;
        }
    }

    private static final class DiffSummary {
        private final int additions;
        private final int deletions;
        private final ArrayNode hunks;

        private DiffSummary(int additions, int deletions, ArrayNode hunks) {
            this.additions = additions;
            this.deletions = deletions;
            this.hunks = hunks;
        }
    }

    private enum DiffType {
        CONTEXT("context"),
        REMOVED("removed"),
        ADDED("added");

        private final String jsonName;

        DiffType(String jsonName) {
            this.jsonName = jsonName;
        }
    }

    private static final class DiffOperation {
        private final DiffType type;
        private final String content;
        private Integer oldLine;
        private Integer newLine;

        private DiffOperation(DiffType type, String content) {
            this.type = type;
            this.content = content;
        }
    }

    private static final class SearchCandidate {
        private final int lineIndex;
        private final int score;

        private SearchCandidate(int lineIndex, int score) {
            this.lineIndex = lineIndex;
            this.score = score;
        }
    }

    private static final class EditFailureException extends RuntimeException {
        private final String code;
        private final Integer editIndex;
        private final Integer matchCount;
        private final ArrayNode candidates;
        private final String retry;
        private final String matchStrategy;
        private final List<Integer> conflictingEditIndexes;

        private EditFailureException(String code,
                                     String message,
                                     Integer editIndex,
                                     Integer matchCount,
                                     ArrayNode candidates,
                                     String retry,
                                     String matchStrategy,
                                     List<Integer> conflictingEditIndexes) {
            super(message);
            this.code = code;
            this.editIndex = editIndex;
            this.matchCount = matchCount;
            this.candidates = candidates;
            this.retry = retry;
            this.matchStrategy = matchStrategy;
            this.conflictingEditIndexes = conflictingEditIndexes;
        }
    }
}

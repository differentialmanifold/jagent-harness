package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepSearchEngine;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepSearchEngine.GrepMatch;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepSearchEngine.GrepResult;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.SearchFileMatcher;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class GrepTool implements ToolDefinition {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;
    private static final int MAX_PREVIEW_CHARS = 2000;

    private final ObjectMapper objectMapper;
    private final WorkspacePathResolver pathResolver;
    private final RipgrepSearchEngine ripgrepSearchEngine;

    public GrepTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        this(objectMapper, pathResolver, RipgrepSearchEngine.unavailable());
    }

    public GrepTool(ObjectMapper objectMapper,
                    WorkspacePathResolver pathResolver,
                    RipgrepSearchEngine ripgrepSearchEngine) {
        this.objectMapper = objectMapper;
        this.pathResolver = pathResolver;
        this.ripgrepSearchEngine = ripgrepSearchEngine;
    }

    @Override
    public String getName() {
        return "grep";
    }

    @Override
    public String getDescription() {
        return "Search file contents with a regular expression by default. Returns matching lines with "
                + "file paths and line numbers. Set literal to true for exact text. Hidden files are included; "
                + "ripgrep honors repository ignore files, while Java fallback uses fixed directory exclusions.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("pattern", ToolSchemas.stringProperty(
                objectMapper,
                "Portable single-line regular expression to search for. Look-around and backreferences "
                        + "are not supported. Set literal to true for exact text."));
        ObjectNode path = ToolSchemas.stringProperty(
                objectMapper,
                "Workspace-relative or absolute file or directory to search. Default .");
        path.put("default", ".");
        properties.set("path", path);
        properties.set("glob", ToolSchemas.stringProperty(
                objectMapper,
                "Optional include glob relative to path when searching a directory, "
                        + "such as *.java or **/*.spec.ts. A glob without / matches file names at any depth."));
        ObjectNode ignoreCase = ToolSchemas.booleanProperty(
                objectMapper,
                "Case-insensitive search. Default false.");
        ignoreCase.put("default", false);
        properties.set("ignoreCase", ignoreCase);
        ObjectNode literal = ToolSchemas.booleanProperty(
                objectMapper,
                "Treat pattern as exact text instead of a regular expression. Default false.");
        literal.put("default", false);
        properties.set("literal", literal);
        ObjectNode limit = ToolSchemas.integerProperty(
                objectMapper,
                "Maximum number of matching lines to return. Default 100, maximum 1000.");
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);
        limit.put("default", DEFAULT_LIMIT);
        properties.set("limit", limit);
        return ToolSchemas.objectSchema(objectMapper, properties, "pattern");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        context.getStopSignal().throwIfAborted();
        String pattern = ToolArguments.requiredText(arguments, "pattern");
        if (pattern.indexOf('\n') >= 0 || pattern.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("pattern must be single-line");
        }

        Path target = pathResolver.resolve(context, arguments.path("path").asText("."));
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Path not found: " + pathResolver.relative(context, target));
        }
        if (!Files.isDirectory(target) && !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Path is not a file or directory: "
                    + pathResolver.relative(context, target));
        }

        String glob = includeGlob(arguments.path("glob").asText(""));
        SearchFileMatcher fileMatcher = glob.isEmpty() ? null : new SearchFileMatcher(glob);
        boolean ignoreCase = arguments.path("ignoreCase").asBoolean(false);
        boolean literal = arguments.path("literal").asBoolean(false);
        int limit = arguments.path("limit").asInt(DEFAULT_LIMIT);
        validateLimit(limit);
        Pattern compiledPattern = compilePattern(pattern, ignoreCase, literal);

        Optional<GrepResult> ripgrepResult = ripgrepSearchEngine.grep(
                target,
                pattern,
                glob,
                ignoreCase,
                literal,
                limit,
                context.getStopSignal());
        if (ripgrepResult.isPresent()) {
            return result(context, target, pattern, glob, limit, ripgrepResult.get(), "ripgrep");
        }

        GrepResult javaResult = searchWithJava(
                context,
                target,
                fileMatcher,
                compiledPattern,
                limit);
        return result(context, target, pattern, glob, limit, javaResult, "java");
    }

    private GrepResult searchWithJava(ToolContext context,
                                      Path target,
                                      SearchFileMatcher fileMatcher,
                                      Pattern compiledPattern,
                                      int limit) {
        List<GrepMatch> matches = new ArrayList<GrepMatch>();
        boolean[] truncated = new boolean[] { false };

        if (Files.isDirectory(target)) {
            try {
                Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        context.getStopSignal().throwIfAborted();
                        if (!target.equals(directory) && SearchFileMatcher.isExcludedDirectory(directory)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        context.getStopSignal().throwIfAborted();
                        if (attributes.isRegularFile()) {
                            searchFile(
                                    context,
                                    target,
                                    file,
                                    fileMatcher,
                                    compiledPattern,
                                    limit,
                                    matches,
                                    truncated);
                        }
                        return truncated[0] ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, java.io.IOException exception) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (StopRequestedException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to search path: "
                        + pathResolver.relative(context, target), e);
            }
        } else {
            searchFile(
                    context,
                    target.getParent(),
                    target,
                    null,
                    compiledPattern,
                    limit,
                    matches,
                    truncated);
        }
        return new GrepResult(matches, truncated[0]);
    }

    private ToolExecutionResult result(ToolContext context,
                                       Path target,
                                       String pattern,
                                       String glob,
                                       int limit,
                                       GrepResult grepResult,
                                       String engine) {
        List<GrepMatch> sortedMatches = new ArrayList<GrepMatch>(grepResult.getMatches());
        Collections.sort(sortedMatches, Comparator
                .comparing((GrepMatch match) -> pathResolver.relative(context, match.getPath()))
                .thenComparingInt(GrepMatch::getLine));
        ArrayNode matches = objectMapper.createArrayNode();
        for (GrepMatch grepMatch : sortedMatches) {
            ObjectNode match = objectMapper.createObjectNode();
            match.put("path", pathResolver.relative(context, grepMatch.getPath()));
            match.put("line", grepMatch.getLine());
            match.put("preview", grepMatch.getPreview());
            matches.add(match);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("pattern", pattern);
        result.put("path", pathResolver.relative(context, target));
        if (!glob.isEmpty()) {
            result.put("glob", glob);
        }
        result.put("limit", limit);
        result.put("truncated", grepResult.isTruncated());
        result.put("engine", engine);
        result.set("matches", matches);
        return ToolExecutionResult.of(result.toString());
    }

    private void searchFile(ToolContext context,
                            Path root,
                            Path path,
                            SearchFileMatcher fileMatcher,
                            Pattern pattern,
                            int limit,
                            List<GrepMatch> matches,
                            boolean[] truncated) {
        context.getStopSignal().throwIfAborted();
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        if (fileMatcher != null && !fileMatcher.matches(relative)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            int lineNumber = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                context.getStopSignal().throwIfAborted();
                lineNumber++;
                if (!pattern.matcher(line).find()) {
                    continue;
                }
                if (matches.size() >= limit) {
                    truncated[0] = true;
                    return;
                }
                matches.add(new GrepMatch(path, lineNumber, truncatePreview(line)));
            }
        } catch (StopRequestedException e) {
            throw e;
        } catch (Exception ignored) {
            // Binary, malformed, or unreadable files are skipped by design.
        }
    }

    private Pattern compilePattern(String pattern, boolean ignoreCase, boolean literal) {
        if (!literal) {
            validatePortableRegex(pattern);
        }
        int flags = Pattern.UNICODE_CHARACTER_CLASS;
        if (ignoreCase) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        String expression = literal ? Pattern.quote(pattern) : pattern;
        try {
            return Pattern.compile(expression, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression: " + e.getDescription(), e);
        }
    }

    private void validatePortableRegex(String expression) {
        boolean escaped = false;
        boolean inCharacterClass = false;
        for (int index = 0; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (escaped) {
                if (character >= '1' && character <= '9') {
                    throw unsupportedRegexFeature("backreferences");
                }
                if (isUnsupportedRegexEscape(expression, index)) {
                    throw unsupportedRegexFeature("unsupported escapes");
                }
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '[') {
                inCharacterClass = true;
                continue;
            }
            if (character == ']' && inCharacterClass) {
                inCharacterClass = false;
                continue;
            }
            if (inCharacterClass) {
                continue;
            }
            if (character == '(' && index + 2 < expression.length()
                    && expression.charAt(index + 1) == '?') {
                char marker = expression.charAt(index + 2);
                if (marker == '=' || marker == '!') {
                    throw unsupportedRegexFeature("look-around");
                }
                if (marker == '<' && index + 3 < expression.length()) {
                    char lookBehindMarker = expression.charAt(index + 3);
                    if (lookBehindMarker == '=' || lookBehindMarker == '!') {
                        throw unsupportedRegexFeature("look-around");
                    }
                }
                if (marker == '>') {
                    throw unsupportedRegexFeature("atomic groups");
                }
                if (hasUnsupportedInlineFlag(expression, index + 2)) {
                    throw unsupportedRegexFeature("unsupported inline flags");
                }
            }
            if (character == '+' && index > 0) {
                if (isPossessiveQuantifier(expression, index)) {
                    throw unsupportedRegexFeature("possessive quantifiers");
                }
            }
        }
    }

    private boolean isUnsupportedRegexEscape(String expression, int index) {
        char character = expression.charAt(index);
        switch (character) {
            case '0':
            case 'c':
            case 'e':
            case 'G':
            case 'h':
            case 'H':
            case 'n':
            case 'Q':
            case 'E':
            case 'R':
            case 'v':
            case 'V':
            case 'X':
            case 'Z':
                return true;
            case 'k':
                return index + 1 < expression.length() && expression.charAt(index + 1) == '<';
            default:
                return false;
        }
    }

    private boolean hasUnsupportedInlineFlag(String expression, int start) {
        int end = start;
        while (end < expression.length()) {
            char character = expression.charAt(end);
            if (character == ':' || character == ')') {
                break;
            }
            if (character != '-' && !Character.isLetter(character)) {
                return false;
            }
            end++;
        }
        if (end >= expression.length()) {
            return false;
        }
        for (int index = start; index < end; index++) {
            char flag = expression.charAt(index);
            if (flag == 'c' || flag == 'd' || flag == 'u' || flag == 'U') {
                return true;
            }
        }
        return false;
    }

    private boolean isPossessiveQuantifier(String expression, int plusIndex) {
        int quantifierEnd = plusIndex - 1;
        char previous = expression.charAt(quantifierEnd);
        if ((previous == '*' || previous == '+' || previous == '?')
                && !isEscaped(expression, quantifierEnd)) {
            return true;
        }
        if (previous != '}' || isEscaped(expression, quantifierEnd)) {
            return false;
        }
        int open = expression.lastIndexOf('{', quantifierEnd);
        if (open < 0 || isEscaped(expression, open)) {
            return false;
        }
        String range = expression.substring(open + 1, quantifierEnd);
        return range.matches("\\d+(,\\d*)?|,\\d+");
    }

    private boolean isEscaped(String expression, int index) {
        int backslashes = 0;
        for (int cursor = index - 1; cursor >= 0 && expression.charAt(cursor) == '\\'; cursor--) {
            backslashes++;
        }
        return backslashes % 2 != 0;
    }

    private IllegalArgumentException unsupportedRegexFeature(String feature) {
        return new IllegalArgumentException(
                "pattern uses a regular expression feature outside the portable subset: " + feature);
    }

    private String includeGlob(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String glob = pathResolver.normalizePathSeparators(value);
        if (glob.startsWith("!")) {
            throw new IllegalArgumentException("glob must be an include glob and cannot start with !");
        }
        return glob;
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    private String truncatePreview(String line) {
        String preview = line.trim();
        if (preview.length() <= MAX_PREVIEW_CHARS) {
            return preview;
        }
        return preview.substring(0, MAX_PREVIEW_CHARS) + "...";
    }
}

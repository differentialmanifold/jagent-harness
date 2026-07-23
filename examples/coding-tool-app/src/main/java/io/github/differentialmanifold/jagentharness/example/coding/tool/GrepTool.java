package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

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
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class GrepTool implements ToolDefinition {

    private static final int DEFAULT_MAX_RESULTS = 200;
    private static final int MAX_RESULTS = 1000;
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
        return "Search text file contents in a file or directory. Relative paths resolve from the workspace; absolute paths are allowed. Use / as the path separator.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("query", ToolSchemas.stringProperty(objectMapper, "Text to search for."));
        properties.set("path", ToolSchemas.stringProperty(objectMapper, "Workspace-relative or absolute file or directory path. Use / as the path separator. Default ."));
        properties.set("glob", ToolSchemas.stringProperty(objectMapper, "Glob applied to paths relative to the searched directory. Default **/*"));
        properties.set("caseSensitive", ToolSchemas.booleanProperty(objectMapper, "Case-sensitive search. Default true."));
        properties.set("maxResults", ToolSchemas.integerProperty(objectMapper, "Maximum number of matches to return. Default 200, maximum 1000."));
        return ToolSchemas.objectSchema(objectMapper, properties, "query");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        context.getStopSignal().throwIfAborted();
        String query = ToolArguments.requiredText(arguments, "query");
        Path target = pathResolver.resolve(context, arguments.path("path").asText("."));
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Path not found: " + pathResolver.relative(context, target));
        }
        boolean searchDirectory = Files.isDirectory(target);
        if (!searchDirectory && !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Path is not a file or directory: "
                    + pathResolver.relative(context, target));
        }

        String glob = pathResolver.normalizePathSeparators(arguments.path("glob").asText("**/*"));
        boolean caseSensitive = arguments.path("caseSensitive").asBoolean(true);
        int maxResults = arguments.path("maxResults").asInt(DEFAULT_MAX_RESULTS);
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw new IllegalArgumentException("maxResults must be between 1 and " + MAX_RESULTS);
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        if (!searchDirectory) {
            Path relative = target.getParent().relativize(target.toAbsolutePath().normalize());
            if (!matchesGlob(relative, matcher, glob)) {
                return result(
                        context,
                        query,
                        target,
                        glob,
                        maxResults,
                        new GrepResult(Collections.<GrepMatch>emptyList(), false),
                        ripgrepSearchEngine.isAvailable() ? "ripgrep" : "java");
            }
        }

        if (query.indexOf('\n') < 0 && query.indexOf('\r') < 0) {
            Optional<GrepResult> ripgrepResult = ripgrepSearchEngine.grep(
                    target,
                    query,
                    glob,
                    caseSensitive,
                    maxResults,
                    context.getStopSignal());
            if (ripgrepResult.isPresent()) {
                return result(
                        context,
                        query,
                        target,
                        glob,
                        maxResults,
                        ripgrepResult.get(),
                        "ripgrep");
            }
        }

        GrepResult javaResult = searchWithJava(
                context,
                target,
                query,
                glob,
                caseSensitive,
                maxResults);
        return result(context, query, target, glob, maxResults, javaResult, "java");
    }

    private GrepResult searchWithJava(ToolContext context,
                                      Path target,
                                      String query,
                                      String glob,
                                      boolean caseSensitive,
                                      int maxResults) {
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<GrepMatch> matches = new ArrayList<GrepMatch>();
        boolean[] truncated = new boolean[] { false };

        if (Files.isDirectory(target)) {
            try (Stream<Path> stream = Files.walk(target)) {
                Iterator<Path> paths = stream.iterator();
                while (paths.hasNext() && !truncated[0]) {
                    context.getStopSignal().throwIfAborted();
                    Path path = paths.next();
                    if (Files.isRegularFile(path)) {
                        searchFile(
                                context,
                                target,
                                path,
                                matcher,
                                glob,
                                needle,
                                caseSensitive,
                                maxResults,
                                matches,
                                truncated);
                    }
                }
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
                    matcher,
                    glob,
                    needle,
                    caseSensitive,
                    maxResults,
                    matches,
                    truncated);
        }
        return new GrepResult(matches, truncated[0]);
    }

    private ToolExecutionResult result(ToolContext context,
                                       String query,
                                       Path target,
                                       String glob,
                                       int maxResults,
                                       GrepResult grepResult,
                                       String engine) {
        ArrayNode matches = objectMapper.createArrayNode();
        for (GrepMatch grepMatch : grepResult.getMatches()) {
            ObjectNode match = objectMapper.createObjectNode();
            match.put("path", pathResolver.relative(context, grepMatch.getPath()));
            match.put("line", grepMatch.getLine());
            match.put("preview", grepMatch.getPreview());
            matches.add(match);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("query", query);
        result.put("path", pathResolver.relative(context, target));
        result.put("glob", glob);
        result.put("maxResults", maxResults);
        result.put("truncated", grepResult.isTruncated());
        result.put("engine", engine);
        result.set("matches", matches);
        return ToolExecutionResult.of(result.toString());
    }

    private void searchFile(ToolContext context,
                            Path root,
                            Path path,
                            PathMatcher matcher,
                            String glob,
                            String needle,
                            boolean caseSensitive,
                            int maxResults,
                            List<GrepMatch> matches,
                            boolean[] truncated) {
        context.getStopSignal().throwIfAborted();
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        if (!matchesGlob(relative, matcher, glob)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            int lineNumber = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                context.getStopSignal().throwIfAborted();
                lineNumber++;
                String haystack = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
                if (haystack.contains(needle)) {
                    if (matches.size() >= maxResults) {
                        truncated[0] = true;
                        return;
                    }
                    matches.add(new GrepMatch(path, lineNumber, truncatePreview(line)));
                }
            }
        } catch (StopRequestedException e) {
            throw e;
        } catch (Exception ignored) {
            // Binary or unreadable files are skipped by design.
        }
    }

    private boolean matchesGlob(Path relative, PathMatcher matcher, String glob) {
        if (glob == null || glob.trim().isEmpty() || "**/*".equals(glob)) {
            return true;
        }
        if (matcher.matches(relative)) {
            return true;
        }
        return relative.getFileName() != null && matcher.matches(relative.getFileName());
    }

    private String truncatePreview(String line) {
        String preview = line.trim();
        if (preview.length() <= MAX_PREVIEW_CHARS) {
            return preview;
        }
        return preview.substring(0, MAX_PREVIEW_CHARS) + "...";
    }
}

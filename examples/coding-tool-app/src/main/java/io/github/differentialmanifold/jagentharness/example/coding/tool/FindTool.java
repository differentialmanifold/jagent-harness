package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepSearchEngine;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepSearchEngine.FindResult;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class FindTool implements ToolDefinition {

    private static final int DEFAULT_MAX_RESULTS = 200;
    private static final int MAX_RESULTS = 1000;
    private static final String DEFAULT_EXCLUDE = ".git/**,target/**,node_modules/**,dist/**,build/**";

    private final ObjectMapper objectMapper;
    private final WorkspacePathResolver pathResolver;
    private final RipgrepSearchEngine ripgrepSearchEngine;

    public FindTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        this(objectMapper, pathResolver, RipgrepSearchEngine.unavailable());
    }

    public FindTool(ObjectMapper objectMapper,
                    WorkspacePathResolver pathResolver,
                    RipgrepSearchEngine ripgrepSearchEngine) {
        this.objectMapper = objectMapper;
        this.pathResolver = pathResolver;
        this.ripgrepSearchEngine = ripgrepSearchEngine;
    }

    @Override
    public String getName() {
        return "find";
    }

    @Override
    public String getDescription() {
        return "Find files and directories by glob, name, type, depth, and exclusions. Relative paths resolve from the workspace; absolute paths are allowed. Prefer this over bash find.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(objectMapper, "Workspace-relative or absolute directory path. Default ."));
        properties.set("glob", ToolSchemas.stringProperty(objectMapper, "Glob applied to paths relative to the searched directory. Default **/*"));
        properties.set("name", ToolSchemas.stringProperty(objectMapper, "Glob applied only to file or directory names, such as *.java. Optional."));
        properties.set("type", ToolSchemas.stringProperty(objectMapper, "all, file, or directory. Default all."));
        properties.set("maxDepth", ToolSchemas.integerProperty(objectMapper, "Maximum directory depth below path. Default unlimited."));
        properties.set("maxResults", ToolSchemas.integerProperty(objectMapper, "Maximum number of results to return. Default 200, maximum 1000."));
        properties.set("exclude", ToolSchemas.stringProperty(objectMapper, "Comma-separated glob patterns relative to the searched directory to skip. Default .git/**,target/**,node_modules/**,dist/**,build/**."));
        properties.set("includeHidden", ToolSchemas.booleanProperty(objectMapper, "Include hidden files and directories. Default false."));
        return ToolSchemas.objectSchema(objectMapper, properties);
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        context.getStopSignal().throwIfAborted();
        Path root = pathResolver.resolve(context, arguments.path("path").asText("."));
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Directory not found: " + pathResolver.relative(context, root));
        }

        String glob = pathResolver.normalizePathSeparators(arguments.path("glob").asText("**/*"));
        String name = arguments.path("name").asText("");
        String type = arguments.path("type").asText("all");
        int maxDepth = arguments.path("maxDepth").asInt(Integer.MAX_VALUE);
        int maxResults = arguments.path("maxResults").asInt(DEFAULT_MAX_RESULTS);
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1");
        }
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw new IllegalArgumentException("maxResults must be between 1 and " + MAX_RESULTS);
        }
        boolean includeHidden = arguments.path("includeHidden").asBoolean(false);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        PathMatcher nameMatcher = isBlank(name) ? null : FileSystems.getDefault().getPathMatcher("glob:" + name);
        List<String> excludePatterns = excludePatterns(arguments.path("exclude").asText(DEFAULT_EXCLUDE));
        List<PathMatcher> excludeMatchers = excludeMatchers(excludePatterns);

        if ("file".equalsIgnoreCase(type)) {
            Optional<FindResult> ripgrepResult = ripgrepSearchEngine.findFiles(
                    root,
                    glob,
                    name,
                    nameMatcher,
                    maxDepth,
                    maxResults,
                    excludePatterns,
                    includeHidden,
                    context.getStopSignal());
            if (ripgrepResult.isPresent()) {
                return result(
                        context,
                        root,
                        glob,
                        name,
                        type,
                        maxResults,
                        ripgrepResult.get().getPaths(),
                        ripgrepResult.get().isTruncated(),
                        "ripgrep");
            }
        }

        List<Path> matches = new ArrayList<Path>();
        boolean[] truncated = new boolean[] { false };

        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                context.getStopSignal().throwIfAborted();
                if (root.equals(dir)) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = root.relativize(dir.toAbsolutePath().normalize());
                if ((!includeHidden && hasHiddenSegment(relative)) || isExcluded(relative, excludeMatchers)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (addMatchIfNeeded(dir, relative, type, glob, matcher, nameMatcher, maxResults, matches)) {
                    return FileVisitResult.CONTINUE;
                }
                truncated[0] = true;
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                context.getStopSignal().throwIfAborted();
                Path relative = root.relativize(file.toAbsolutePath().normalize());
                if ((!includeHidden && hasHiddenSegment(relative)) || isExcluded(relative, excludeMatchers)) {
                    return FileVisitResult.CONTINUE;
                }
                if (addMatchIfNeeded(file, relative, type, glob, matcher, nameMatcher, maxResults, matches)) {
                    return FileVisitResult.CONTINUE;
                }
                truncated[0] = true;
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return result(
                context,
                root,
                glob,
                name,
                type,
                maxResults,
                matches,
                truncated[0],
                "java");
    }

    private ToolExecutionResult result(ToolContext context,
                                       Path root,
                                       String glob,
                                       String name,
                                       String type,
                                       int maxResults,
                                       List<Path> matches,
                                       boolean truncated,
                                       String engine) {
        List<Path> sortedMatches = new ArrayList<Path>(matches);
        Collections.sort(sortedMatches, Comparator.comparing(path -> pathResolver.relative(context, path)));
        ArrayNode entries = objectMapper.createArrayNode();
        for (Path match : sortedMatches) {
            context.getStopSignal().throwIfAborted();
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("path", pathResolver.relative(context, match));
            entry.put("type", Files.isDirectory(match) ? "directory" : "file");
            entries.add(entry);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", pathResolver.relative(context, root));
        result.put("glob", glob);
        if (!isBlank(name)) {
            result.put("name", name);
        }
        result.put("type", type);
        result.put("maxResults", maxResults);
        result.put("truncated", truncated);
        result.put("engine", engine);
        result.set("matches", entries);
        return ToolExecutionResult.of(result.toString());
    }

    private boolean addMatchIfNeeded(Path path,
                                     Path relative,
                                     String type,
                                     String glob,
                                     PathMatcher matcher,
                                     PathMatcher nameMatcher,
                                     int maxResults,
                                     List<Path> matches) {
        if (!matchesType(path, type) || !matchesGlob(relative, matcher, glob)) {
            return true;
        }
        if (nameMatcher != null && (relative.getFileName() == null || !nameMatcher.matches(relative.getFileName()))) {
            return true;
        }
        if (matches.size() >= maxResults) {
            return false;
        }
        matches.add(path);
        return true;
    }

    private List<String> excludePatterns(String exclude) {
        String effectiveExclude = isBlank(exclude) ? DEFAULT_EXCLUDE : exclude;
        List<String> patterns = new ArrayList<String>();
        String[] parts = effectiveExclude.split(",");
        for (String part : parts) {
            String pattern = part == null ? "" : pathResolver.normalizePathSeparators(part.trim());
            if (!pattern.isEmpty()) {
                patterns.add(pattern);
            }
        }
        return patterns;
    }

    private List<PathMatcher> excludeMatchers(List<String> excludePatterns) {
        List<PathMatcher> matchers = new ArrayList<PathMatcher>();
        for (String pattern : excludePatterns) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
        }
        return matchers;
    }

    private boolean isExcluded(Path relative, List<PathMatcher> matchers) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relative)) {
                return true;
            }
            if (relative.getFileName() != null && matcher.matches(relative.getFileName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasHiddenSegment(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(".") && name.length() > 1) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesType(Path path, String type) {
        if ("file".equalsIgnoreCase(type)) {
            return Files.isRegularFile(path);
        }
        if ("directory".equalsIgnoreCase(type)) {
            return Files.isDirectory(path);
        }
        return true;
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

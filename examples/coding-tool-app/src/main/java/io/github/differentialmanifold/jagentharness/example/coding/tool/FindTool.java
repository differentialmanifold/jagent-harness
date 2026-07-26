package io.github.differentialmanifold.jagentharness.example.coding.tool;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepSearchEngine;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepSearchEngine.FindResult;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.SearchFileMatcher;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class FindTool implements ToolDefinition {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

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
        return "Find files by glob pattern. Relative paths resolve from the workspace. "
                + "Use / as the path separator and use ls to inspect a directory. Hidden files are included; "
                + "ripgrep honors repository ignore files, while Java fallback uses fixed directory exclusions.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("pattern", ToolSchemas.stringProperty(
                objectMapper,
                "Glob pattern relative to path, such as *.java, **/*.json, or src/**/*.spec.ts. "
                        + "A pattern without / matches file names at any depth. Use /."));
        ObjectNode path = ToolSchemas.stringProperty(
                objectMapper,
                "Workspace-relative or absolute directory to search. Default .");
        path.put("default", ".");
        properties.set("path", path);
        ObjectNode limit = ToolSchemas.integerProperty(
                objectMapper,
                "Maximum number of files to return. Default 100, maximum 1000.");
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);
        limit.put("default", DEFAULT_LIMIT);
        properties.set("limit", limit);
        return ToolSchemas.objectSchema(objectMapper, properties, "pattern");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        context.getStopSignal().throwIfAborted();
        String pattern = includePattern(arguments, "pattern");
        SearchFileMatcher matcher = new SearchFileMatcher(pattern);
        Path root = pathResolver.resolve(context, arguments.path("path").asText("."));
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Directory not found: " + pathResolver.relative(context, root));
        }
        int limit = arguments.path("limit").asInt(DEFAULT_LIMIT);
        validateLimit(limit);

        Optional<FindResult> ripgrepResult = ripgrepSearchEngine.findFiles(
                root,
                pattern,
                limit,
                context.getStopSignal());
        if (ripgrepResult.isPresent()) {
            return result(context, root, pattern, limit, ripgrepResult.get(), "ripgrep");
        }

        FindResult javaResult = findWithJava(context, root, matcher, limit);
        return result(context, root, pattern, limit, javaResult, "java");
    }

    private FindResult findWithJava(ToolContext context,
                                    Path root,
                                    SearchFileMatcher matcher,
                                    int limit) throws Exception {
        List<Path> files = new ArrayList<Path>();
        boolean[] truncated = new boolean[] { false };

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                context.getStopSignal().throwIfAborted();
                if (!root.equals(directory) && SearchFileMatcher.isExcludedDirectory(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                context.getStopSignal().throwIfAborted();
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = root.relativize(file.toAbsolutePath().normalize());
                if (!matcher.matches(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                if (files.size() >= limit) {
                    truncated[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                files.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, java.io.IOException exception) {
                return FileVisitResult.CONTINUE;
            }
        });
        return new FindResult(files, truncated[0]);
    }

    private ToolExecutionResult result(ToolContext context,
                                       Path root,
                                       String pattern,
                                       int limit,
                                       FindResult findResult,
                                       String engine) {
        List<Path> sortedFiles = new ArrayList<Path>(findResult.getPaths());
        Collections.sort(sortedFiles, Comparator.comparing(path -> pathResolver.relative(context, path)));

        ArrayNode files = objectMapper.createArrayNode();
        for (Path file : sortedFiles) {
            files.add(pathResolver.relative(context, file));
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("pattern", pattern);
        result.put("path", pathResolver.relative(context, root));
        result.put("limit", limit);
        result.put("truncated", findResult.isTruncated());
        result.put("engine", engine);
        result.set("files", files);
        return ToolExecutionResult.of(result.toString());
    }

    private String includePattern(JsonNode arguments, String name) {
        String pattern = pathResolver.normalizePathSeparators(ToolArguments.requiredText(arguments, name));
        if (pattern.startsWith("!")) {
            throw new IllegalArgumentException(name + " must be an include glob and cannot start with !");
        }
        return pattern;
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

}

package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Stream;

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

public class GrepTool implements ToolDefinition {

    private final ObjectMapper objectMapper;
    private final WorkspacePathResolver pathResolver;

    public GrepTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        this.objectMapper = objectMapper;
        this.pathResolver = pathResolver;
    }

    @Override
    public String getName() {
        return "grep";
    }

    @Override
    public String getDescription() {
        return "Search text file contents inside the workspace.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("query", ToolSchemas.stringProperty(objectMapper, "Text to search for."));
        properties.set("path", ToolSchemas.stringProperty(objectMapper, "Workspace-relative directory path. Default ."));
        properties.set("glob", ToolSchemas.stringProperty(objectMapper, "Glob applied to workspace-relative paths. Default **/*"));
        properties.set("caseSensitive", ToolSchemas.booleanProperty(objectMapper, "Case-sensitive search. Default true."));
        return ToolSchemas.objectSchema(objectMapper, properties, "query");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        String query = ToolArguments.requiredText(arguments, "query");
        Path root = pathResolver.resolve(context, arguments.path("path").asText("."));
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Directory not found: " + pathResolver.relative(context, root));
        }

        String glob = arguments.path("glob").asText("**/*");
        boolean caseSensitive = arguments.path("caseSensitive").asBoolean(true);
        String needle = caseSensitive ? query : query.toLowerCase();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        ArrayNode matches = objectMapper.createArrayNode();

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> searchFile(context, path, matcher, glob, needle, caseSensitive, matches));
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("query", query);
        result.put("path", pathResolver.relative(context, root));
        result.put("glob", glob);
        result.set("matches", matches);
        return ToolExecutionResult.of(result.toString());
    }

    private void searchFile(ToolContext context,
                            Path path,
                            PathMatcher matcher,
                            String glob,
                            String needle,
                            boolean caseSensitive,
                            ArrayNode matches) {
        Path relative = pathResolver.workspaceRoot(context).relativize(path.toAbsolutePath().normalize());
        if (!matchesGlob(relative, matcher, glob)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String haystack = caseSensitive ? line : line.toLowerCase();
                if (haystack.contains(needle)) {
                    ObjectNode match = objectMapper.createObjectNode();
                    match.put("path", relative.toString());
                    match.put("line", i + 1);
                    match.put("preview", line.trim());
                    matches.add(match);
                }
            }
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
}

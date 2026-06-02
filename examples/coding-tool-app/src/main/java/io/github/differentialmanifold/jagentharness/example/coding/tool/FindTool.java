package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class FindTool implements ToolDefinition {

    private final ObjectMapper objectMapper;
    private final WorkspacePathResolver pathResolver;

    public FindTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        this.objectMapper = objectMapper;
        this.pathResolver = pathResolver;
    }

    @Override
    public String getName() {
        return "find";
    }

    @Override
    public String getDescription() {
        return "Find files and directories by glob inside the workspace.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(objectMapper, "Workspace-relative directory path. Default ."));
        properties.set("glob", ToolSchemas.stringProperty(objectMapper, "Glob applied to workspace-relative paths. Default **/*"));
        properties.set("type", ToolSchemas.stringProperty(objectMapper, "all, file, or directory. Default all."));
        return ToolSchemas.objectSchema(objectMapper, properties);
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        Path root = pathResolver.resolve(context, arguments.path("path").asText("."));
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Directory not found: " + pathResolver.relative(context, root));
        }

        String glob = arguments.path("glob").asText("**/*");
        String type = arguments.path("type").asText("all");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<Path> matches = new ArrayList<Path>();

        try (Stream<Path> stream = Files.walk(root)) {
            stream.forEach(path -> {
                if (root.equals(path)) {
                    return;
                }
                if (!matchesType(path, type)) {
                    return;
                }
                Path relative = pathResolver.workspaceRoot(context).relativize(path.toAbsolutePath().normalize());
                if (matchesGlob(relative, matcher, glob)) {
                    matches.add(path);
                }
            });
        }
        Collections.sort(matches, Comparator.comparing(path -> pathResolver.relative(context, path)));

        ArrayNode entries = objectMapper.createArrayNode();
        for (Path match : matches) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("path", pathResolver.relative(context, match));
            entry.put("type", Files.isDirectory(match) ? "directory" : "file");
            entries.add(entry);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", pathResolver.relative(context, root));
        result.put("glob", glob);
        result.set("matches", entries);
        return ToolExecutionResult.of(result.toString());
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
}

package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class LsTool implements ToolDefinition {

    private final ObjectMapper objectMapper;
    private final WorkspacePathResolver pathResolver;

    public LsTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        this.objectMapper = objectMapper;
        this.pathResolver = pathResolver;
    }

    @Override
    public String getName() {
        return "ls";
    }

    @Override
    public String getDescription() {
        return "List files and directories. Relative paths resolve from the workspace; absolute paths are allowed.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(objectMapper, "Workspace-relative or absolute directory path. Default ."));
        return ToolSchemas.objectSchema(objectMapper, properties);
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        Path path = pathResolver.resolve(context, arguments.path("path").asText("."));
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Directory not found: " + pathResolver.relative(context, path));
        }

        List<Path> paths = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path child : stream) {
                paths.add(child);
            }
        }
        Collections.sort(paths, Comparator.comparing(child -> child.getFileName().toString()));

        ArrayNode entries = objectMapper.createArrayNode();
        for (Path child : paths) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("name", child.getFileName().toString());
            entry.put("path", pathResolver.relative(context, child));
            entry.put("type", Files.isDirectory(child) ? "directory" : "file");
            entries.add(entry);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", pathResolver.relative(context, path));
        result.set("entries", entries);
        return ToolExecutionResult.of(result.toString());
    }
}

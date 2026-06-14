package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.io.BufferedReader;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class ReadTool implements ToolDefinition {

    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_LIMIT = 2000;

    private final ObjectMapper objectMapper;
    private final WorkspacePathResolver pathResolver;

    public ReadTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        this.objectMapper = objectMapper;
        this.pathResolver = pathResolver;
    }

    @Override
    public String getName() {
        return "read";
    }

    @Override
    public String getDescription() {
        return "Read UTF-8 text lines from a file inside the workspace.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(objectMapper, "Workspace-relative file path."));
        properties.set("offset", ToolSchemas.integerProperty(
                objectMapper,
                "One-based line number to start reading from. Default 1."));
        properties.set("limit", ToolSchemas.integerProperty(
                objectMapper,
                "Maximum number of lines to return. Default and maximum 2000."));
        return ToolSchemas.objectSchema(objectMapper, properties, "path");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        context.getStopSignal().throwIfAborted();
        Path path = pathResolver.resolve(context, ToolArguments.requiredText(arguments, "path"));
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found: " + pathResolver.relative(context, path));
        }

        int offset = arguments.path("offset").asInt(1);
        int limit = arguments.path("limit").asInt(DEFAULT_LIMIT);
        if (offset < 1) {
            throw new IllegalArgumentException("offset must be at least 1");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }

        ReadSlice slice = readLines(context, path, offset, limit);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", pathResolver.relative(context, path));
        result.put("type", "text");
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("lines", slice.lines.size());
        result.put("totalLines", slice.totalLines);
        result.put("truncated", slice.hasMore);
        result.put("content", String.join("\n", slice.lines));
        return ToolExecutionResult.of(result.toString());
    }

    private ReadSlice readLines(ToolContext context, Path path, int offset, int limit) throws Exception {
        List<String> selected = new ArrayList<String>();
        int totalLines = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                context.getStopSignal().throwIfAborted();
                totalLines++;
                if (line.indexOf('\0') >= 0) {
                    throw unsupportedTextFile(path);
                }
                if (totalLines >= offset && selected.size() < limit) {
                    selected.add(line);
                }
            }
        } catch (MalformedInputException e) {
            throw unsupportedTextFile(path);
        }
        int selectedEnd = Math.min(offset - 1, totalLines) + selected.size();
        return new ReadSlice(selected, totalLines, selectedEnd < totalLines);
    }

    private IllegalArgumentException unsupportedTextFile(Path path) {
        return new IllegalArgumentException("Read tool supports UTF-8 text files only: " + path.getFileName());
    }

    private static class ReadSlice {
        private final List<String> lines;
        private final int totalLines;
        private final boolean hasMore;

        private ReadSlice(List<String> lines, int totalLines, boolean hasMore) {
            this.lines = lines;
            this.totalLines = totalLines;
            this.hasMore = hasMore;
        }
    }
}

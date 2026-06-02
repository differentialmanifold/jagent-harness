package io.github.differentialmanifold.jagentharness.core.tool.builtin;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

public class ReadTool implements ToolDefinition {

    private final ObjectMapper objectMapper;

    public ReadTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "read";
    }

    @Override
    public String getDescription() {
        return "Read a UTF-8 text file under the workspace root or agent config root and return its content.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(
                objectMapper,
                "File path. Relative paths resolve under the workspace root when present, otherwise under the agent config root. Absolute paths must stay under an allowed root."));
        return ToolSchemas.objectSchema(objectMapper, properties, "path");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        Path path = resolve(context, ToolArguments.requiredText(arguments, "path"));
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found: " + displayPath(context, path));
        }

        String content = readUtf8Text(path, Files.readAllBytes(path));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", displayPath(context, path));
        result.put("type", "text");
        result.put("content", content);
        return ToolExecutionResult.of(result.toString());
    }

    private Path resolve(ToolContext context, String input) {
        Path path = Paths.get(input);
        if (path.isAbsolute()) {
            Path resolved = path.normalize();
            if (isUnderAllowedRoot(context, resolved)) {
                return resolved;
            }
            throw new IllegalArgumentException("Path is outside allowed read roots: " + input);
        }

        Path root = defaultRelativeRoot(context);
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes allowed read root: " + input);
        }
        return resolved;
    }

    private Path defaultRelativeRoot(ToolContext context) {
        if (context != null && context.getWorkspaceRoot() != null) {
            return context.getWorkspaceRoot().toAbsolutePath().normalize();
        }
        if (context != null && context.getConfigRoot() != null) {
            return context.getConfigRoot().toAbsolutePath().normalize();
        }
        throw new IllegalArgumentException("No workspace root or agent config root is configured for relative paths.");
    }

    private boolean isUnderAllowedRoot(ToolContext context, Path path) {
        for (Path root : allowedRoots(context)) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private List<Path> allowedRoots(ToolContext context) {
        List<Path> roots = new ArrayList<Path>();
        if (context != null && context.getWorkspaceRoot() != null) {
            roots.add(context.getWorkspaceRoot().toAbsolutePath().normalize());
        }
        if (context != null && context.getConfigRoot() != null) {
            roots.add(context.getConfigRoot().toAbsolutePath().normalize());
        }
        return roots;
    }

    private String displayPath(ToolContext context, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (context != null && context.getWorkspaceRoot() != null) {
            Path workspaceRoot = context.getWorkspaceRoot().toAbsolutePath().normalize();
            if (normalized.startsWith(workspaceRoot)) {
                return workspaceRoot.relativize(normalized).toString();
            }
        }
        return normalized.toString();
    }

    private String readUtf8Text(Path path, byte[] bytes) {
        if (containsNullByte(bytes)) {
            throw new IllegalArgumentException("Read tool supports UTF-8 text files only: " + path.getFileName());
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Read tool supports UTF-8 text files only: " + path.getFileName());
        }
    }

    private boolean containsNullByte(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }
}

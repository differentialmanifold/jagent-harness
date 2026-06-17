package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;

public class WriteTool implements ToolDefinition {

    private final ObjectMapper objectMapper;
    private final WorkspacePathResolver pathResolver;

    public WriteTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        this.objectMapper = objectMapper;
        this.pathResolver = pathResolver;
    }

    @Override
    public String getName() {
        return "write";
    }

    @Override
    public String getDescription() {
        return "Create a new UTF-8 text file or intentionally overwrite a whole file. Relative paths resolve from the workspace; absolute paths are allowed. Prefer edit for localized changes to existing files.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(objectMapper, "Workspace-relative or absolute file path."));
        properties.set("content", ToolSchemas.stringProperty(objectMapper, "File content."));
        return ToolSchemas.objectSchema(objectMapper, properties, "path", "content");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        Path path = pathResolver.resolve(context, ToolArguments.requiredText(arguments, "path"));
        String content = ToolArguments.requiredString(arguments, "content");
        requireApprovalIfOutsideWorkspace(context, path);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", pathResolver.relative(context, path));
        result.put("bytes", content.getBytes(StandardCharsets.UTF_8).length);
        return ToolExecutionResult.of(result.toString());
    }

    private void requireApprovalIfOutsideWorkspace(ToolContext context, Path path) throws Exception {
        if (pathResolver.isInsideWorkspace(context, path)) {
            return;
        }
        context.requestApproval(new ToolApprovalRequest(
                "Approve write outside workspace",
                "The write tool wants to create or overwrite a file outside the current workspace.",
                "write",
                path.toAbsolutePath().normalize().toString()));
    }
}

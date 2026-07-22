package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.FileMutationCoordinator;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.Utf8Text;
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
        context.getStopSignal().throwIfAborted();
        String requestedPath = ToolArguments.requiredText(arguments, "path");
        String content = ToolArguments.requiredString(arguments, "content");
        byte[] bytes = Utf8Text.encode(content, "content");
        Path path = pathResolver.resolve(context, requestedPath);
        Path approvedCanonicalPath = FileMutationCoordinator.canonicalPath(path);
        requireApprovalIfOutsideWorkspace(context, path, approvedCanonicalPath);
        Path parent = path.getParent();
        if (parent != null) {
            createParentDirectories(parent);
        }
        try (FileMutationCoordinator.LockHandle lock = FileMutationCoordinator.acquire(path)) {
            context.getStopSignal().throwIfAborted();
            Path canonicalPath = lock.getCanonicalPath();
            if (!canonicalPath.equals(approvedCanonicalPath)) {
                requireApprovalIfOutsideWorkspace(context, path, canonicalPath);
            }
            try {
                FileMutationCoordinator.writeAtomically(canonicalPath, bytes);
            } catch (FileMutationCoordinator.FileBusyException e) {
                ObjectNode failure = objectMapper.createObjectNode();
                failure.put("code", "FILE_BUSY");
                failure.put("error", e.getMessage());
                failure.put("path", pathResolver.relative(context, path));
                failure.put("target", e.getTarget().toString());
                failure.put(
                        "retry",
                        "Close programs that hold the file open, verify write permissions, and retry the write.");
                return ToolExecutionResult.of(failure.toString());
            } catch (FileMutationCoordinator.ConcurrentFileMutationException e) {
                ObjectNode failure = objectMapper.createObjectNode();
                failure.put("code", "CONCURRENT_MODIFICATION");
                failure.put("error", e.getMessage());
                failure.put("path", pathResolver.relative(context, path));
                failure.put("target", e.getTarget().toString());
                failure.put("retry", "Retry the write after concurrent changes have settled.");
                return ToolExecutionResult.of(failure.toString());
            } catch (FileMutationCoordinator.HardLinkException e) {
                ObjectNode failure = objectMapper.createObjectNode();
                failure.put("code", "HARD_LINK_UNSUPPORTED");
                failure.put("error", e.getMessage());
                failure.put("path", pathResolver.relative(context, path));
                failure.put("target", e.getTarget().toString());
                failure.put("linkCount", e.getLinkCount());
                failure.put(
                        "retry",
                        "Use a workflow that intentionally updates the shared hard-linked file in place.");
                return ToolExecutionResult.of(failure.toString());
            } catch (FileMutationCoordinator.PathChangedException e) {
                ObjectNode failure = objectMapper.createObjectNode();
                failure.put("code", "PATH_CHANGED");
                failure.put("error", e.getMessage());
                failure.put("path", pathResolver.relative(context, path));
                failure.put("approvedTarget", e.getApprovedTarget().toString());
                failure.put("currentTarget", e.getCurrentTarget().toString());
                failure.put(
                        "retry",
                        "Resolve and approve the path's current location before retrying the write.");
                return ToolExecutionResult.of(failure.toString());
            }
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", pathResolver.relative(context, path));
        result.put("bytes", bytes.length);
        return ToolExecutionResult.of(result.toString());
    }

    void createParentDirectories(Path parent) throws IOException {
        Files.createDirectories(parent);
    }

    private void requireApprovalIfOutsideWorkspace(ToolContext context,
                                                   Path requestedPath,
                                                   Path canonicalPath) throws Exception {
        Path workspaceRoot = pathResolver.workspaceRoot(context);
        Path canonicalWorkspaceRoot = FileMutationCoordinator.canonicalPath(workspaceRoot);
        boolean requestedInsideWorkspace = requestedPath.toAbsolutePath().normalize().startsWith(workspaceRoot);
        boolean canonicalInsideWorkspace = canonicalPath.toAbsolutePath().normalize().startsWith(canonicalWorkspaceRoot);
        if (requestedInsideWorkspace && canonicalInsideWorkspace) {
            return;
        }
        context.requestApproval(new ToolApprovalRequest(
                "Approve write outside workspace",
                "The write tool wants to create or overwrite a file outside the current workspace.",
                "write",
                canonicalPath.toAbsolutePath().normalize().toString()));
    }
}

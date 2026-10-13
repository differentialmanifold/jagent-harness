package io.github.differentialmanifold.jagentharness.tools.coding.tool.support;

import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WorkspacePathResolver {
    private final Path workspace;

    public WorkspacePathResolver() {
        this.workspace = null;
    }

    public WorkspacePathResolver(Path workspace) {
        if (workspace == null || !java.nio.file.Files.isDirectory(workspace))
            throw new IllegalArgumentException("Workspace must be a directory");
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    public Path resolve(ToolContext context, String input) {
        Path root = workspaceRoot(context);
        Path path = Paths.get(normalizePathSeparators(input));
        return path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : root.resolve(path).toAbsolutePath().normalize();
    }

    public Path workspaceRoot(ToolContext context) {
        if (workspace != null) return workspace;
        if (context != null && context.getWorkspaceRoot() != null) {
            return context.getWorkspaceRoot().toAbsolutePath().normalize();
        }
        throw new IllegalArgumentException("Workspace root is required for coding tools.");
    }

    public String relative(ToolContext context, Path path) {
        Path root = workspaceRoot(context);
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            return normalizePathSeparators(root.relativize(normalized).toString());
        }
        return normalizePathSeparators(normalized.toString());
    }

    public boolean isInsideWorkspace(ToolContext context, Path path) {
        return path.toAbsolutePath().normalize().startsWith(workspaceRoot(context));
    }

    public String normalizePathSeparators(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }
}

package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import java.nio.file.Path;
import java.nio.file.Paths;

import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;

public class WorkspacePathResolver {

    public Path resolve(ToolContext context, String input) {
        Path root = workspaceRoot(context);
        Path path = Paths.get(input);
        return path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : root.resolve(path).toAbsolutePath().normalize();
    }

    public Path workspaceRoot(ToolContext context) {
        if (context != null && context.getWorkspaceRoot() != null) {
            return context.getWorkspaceRoot().toAbsolutePath().normalize();
        }
        throw new IllegalArgumentException("Workspace root is required for coding tools.");
    }

    public String relative(ToolContext context, Path path) {
        Path root = workspaceRoot(context);
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            return root.relativize(normalized).toString();
        }
        return normalized.toString();
    }

    public boolean isInsideWorkspace(ToolContext context, Path path) {
        return path.toAbsolutePath().normalize().startsWith(workspaceRoot(context));
    }
}

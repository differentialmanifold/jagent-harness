package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import java.nio.file.Path;
import java.nio.file.Paths;

import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;

public class WorkspacePathResolver {

    public Path resolve(ToolContext context, String input) {
        Path root = workspaceRoot(context);
        Path path = Paths.get(input);
        Path resolved = path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes workspace root: " + input);
        }
        return resolved;
    }

    public Path workspaceRoot(ToolContext context) {
        if (context != null && context.getWorkspaceRoot() != null) {
            return context.getWorkspaceRoot().toAbsolutePath().normalize();
        }
        throw new IllegalArgumentException("Workspace root is required for coding tools.");
    }

    public String relative(ToolContext context, Path path) {
        return workspaceRoot(context).relativize(path.toAbsolutePath().normalize()).toString();
    }
}

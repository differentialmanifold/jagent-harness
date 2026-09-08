package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import io.github.differentialmanifold.jagentharness.example.coding.execution.CodingContext;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WorkspacePathResolver {

    public Path resolve(CodingContext context, String input) {
        Path root = workspaceRoot(context);
        Path path = Paths.get(normalizePathSeparators(input));
        return path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : root.resolve(path).toAbsolutePath().normalize();
    }

    public Path workspaceRoot(CodingContext context) {
        if (context != null && context.getWorkspaceRoot() != null) {
            return context.getWorkspaceRoot().toAbsolutePath().normalize();
        }
        throw new IllegalArgumentException("Workspace root is required for coding tools.");
    }

    public String relative(CodingContext context, Path path) {
        Path root = workspaceRoot(context);
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            return normalizePathSeparators(root.relativize(normalized).toString());
        }
        return normalizePathSeparators(normalized.toString());
    }

    public boolean isInsideWorkspace(CodingContext context, Path path) {
        return path.toAbsolutePath().normalize().startsWith(workspaceRoot(context));
    }

    public String normalizePathSeparators(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }
}

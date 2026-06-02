package io.github.differentialmanifold.jagentharness.example.coding.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import io.github.differentialmanifold.jagentharness.spring.web.WorkspaceRootResolver;
import org.springframework.stereotype.Service;

@Service
public class CodingWorkspaceService implements WorkspaceRootResolver {

    @Override
    public String normalizeWorkspacePath(String workspacePath) {
        return workspaceRoot(workspacePath).toString();
    }

    @Override
    public Path resolveWorkspaceRoot(String workspacePath) {
        return workspaceRoot(workspacePath);
    }

    public Path workspaceRoot(String workspacePath) {
        if (workspacePath == null || workspacePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Workspace path is required.");
        }
        Path root = Paths.get(workspacePath.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Workspace directory not found: " + root);
        }
        return root;
    }
}

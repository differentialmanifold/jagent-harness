package io.github.differentialmanifold.jagentharness.example.coding.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.stereotype.Service;

@Service
public class CodingWorkspaceService {

    public String normalizeWorkspacePath(String workspacePath) {
        return workspaceRoot(workspacePath).toString();
    }

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

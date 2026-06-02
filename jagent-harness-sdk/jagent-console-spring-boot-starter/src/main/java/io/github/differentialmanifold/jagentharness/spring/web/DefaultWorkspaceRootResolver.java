package io.github.differentialmanifold.jagentharness.spring.web;

import java.nio.file.Path;

public class DefaultWorkspaceRootResolver implements WorkspaceRootResolver {

    @Override
    public String normalizeWorkspacePath(String workspacePath) {
        if (workspacePath == null) {
            return null;
        }
        String trimmed = workspacePath.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public Path resolveWorkspaceRoot(String workspacePath) {
        return null;
    }
}

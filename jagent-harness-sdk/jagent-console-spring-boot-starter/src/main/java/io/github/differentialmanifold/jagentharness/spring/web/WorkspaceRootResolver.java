package io.github.differentialmanifold.jagentharness.spring.web;

import java.nio.file.Path;

public interface WorkspaceRootResolver {

    String normalizeWorkspacePath(String workspacePath);

    Path resolveWorkspaceRoot(String workspacePath);
}

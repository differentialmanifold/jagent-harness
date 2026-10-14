package io.github.differentialmanifold.jagentharness.protocol;

/** Read-only directory lookup delivered through the runner's outbound poll. */
public class WorkspaceRequest {
    public String requestId;
    public String path;
}

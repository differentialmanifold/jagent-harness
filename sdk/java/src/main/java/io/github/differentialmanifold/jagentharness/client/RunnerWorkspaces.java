package io.github.differentialmanifold.jagentharness.client;

import io.github.differentialmanifold.jagentharness.protocol.WorkspaceListing;
import java.io.IOException;
import java.nio.file.Path;

/** Optional filesystem support. Device and other workspace-free runners do not need this. */
public interface RunnerWorkspaces {
    WorkspaceListing browse(String path) throws IOException;

    Path resolve(String path) throws IOException;
}

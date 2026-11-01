package io.github.differentialmanifold.jagentharness.client;

import io.github.differentialmanifold.jagentharness.protocol.WorkspaceListing;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Browses directories accessible to the runner's OS account; no inbound listener is opened. */
public final class LocalWorkspaces implements RunnerWorkspaces {
    private final Path defaultRoot;

    public LocalWorkspaces() {
        this(Paths.get(System.getProperty("user.home"), ".jagent", "workspaces"));
    }

    public LocalWorkspaces(Path defaultRoot) {
        this.defaultRoot = defaultRoot.toAbsolutePath().normalize();
    }

    @Override
    public String defaultRoot() {
        return defaultRoot.toString();
    }

    @Override
    public Path resolve(String path) throws IOException {
        if (path == null || path.trim().isEmpty())
            throw new IllegalArgumentException("Select a project workspace on the runner");
        Path requested = Paths.get(path);
        if (!requested.isAbsolute())
            throw new IllegalArgumentException("Workspace must be an absolute path on the runner");
        requested = requested.normalize();
        // Only managed project directories are created automatically. Explicit existing
        // workspaces retain their previous validation and are never silently created.
        if (!Files.exists(requested) && requested.startsWith(defaultRoot)) {
            Path relative = defaultRoot.relativize(requested);
            boolean user =
                    relative.getNameCount() == 2 && "user".equals(relative.getName(0).toString());
            boolean agent =
                    relative.getNameCount() == 3
                            && "agents".equals(relative.getName(0).toString())
                            && relative.getName(1).toString().matches("agent_[A-Za-z0-9_-]+");
            if ((user || agent)
                    && relative.getFileName().toString().matches("project_[A-Za-z0-9_-]+")) {
                Files.createDirectories(requested);
            }
        }
        Path root = requested.toRealPath();
        if (!Files.isDirectory(root))
            throw new IllegalArgumentException("Workspace is not a directory: " + path);
        return root;
    }

    @Override
    public WorkspaceListing browse(String path) throws IOException {
        Path root =
                resolve(
                        path == null || path.trim().isEmpty()
                                ? System.getProperty("user.home")
                                : path);
        WorkspaceListing result = new WorkspaceListing();
        result.path = root.toString();
        result.parent = root.getParent() == null ? null : root.getParent().toString();
        for (Path drive : FileSystems.getDefault().getRootDirectories())
            result.roots.add(drive.toString());
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry)) continue;
                if (result.directories.size() == 1000) {
                    result.truncated = true;
                    break;
                }
                result.directories.add(entry.toString());
            }
        }
        result.directories.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }
}

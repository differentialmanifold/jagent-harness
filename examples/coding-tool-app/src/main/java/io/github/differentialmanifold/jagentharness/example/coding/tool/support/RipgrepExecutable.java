package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import java.nio.file.Path;
import java.util.Objects;

/**
 * An immutable description of a locally installed ripgrep executable.
 */
public final class RipgrepExecutable {

    private final Path path;
    private final String version;

    RipgrepExecutable(Path path, String version) {
        this.path = Objects.requireNonNull(path, "path");
        this.version = Objects.requireNonNull(version, "version");
    }

    public Path getPath() {
        return path;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RipgrepExecutable)) {
            return false;
        }
        RipgrepExecutable that = (RipgrepExecutable) other;
        return path.equals(that.path) && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, version);
    }

    @Override
    public String toString() {
        return "RipgrepExecutable{path=" + path + ", version='" + version + "'}";
    }
}

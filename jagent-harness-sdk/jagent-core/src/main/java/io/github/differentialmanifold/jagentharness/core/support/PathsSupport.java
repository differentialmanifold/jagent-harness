package io.github.differentialmanifold.jagentharness.core.support;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathsSupport {

    public static final String DEFAULT_CONFIG_ROOT = "~/.jagent-harness";

    private PathsSupport() {
    }

    public static Path expandUserHome(String path) {
        String effectivePath = path == null || path.trim().isEmpty() ? DEFAULT_CONFIG_ROOT : path.trim();
        if ("~".equals(effectivePath)) {
            return Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        if (effectivePath.startsWith("~/")) {
            return Paths.get(System.getProperty("user.home"), effectivePath.substring(2)).toAbsolutePath().normalize();
        }
        return Paths.get(effectivePath).toAbsolutePath().normalize();
    }
}

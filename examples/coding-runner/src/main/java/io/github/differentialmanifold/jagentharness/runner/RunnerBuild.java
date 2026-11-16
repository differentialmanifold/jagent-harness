package io.github.differentialmanifold.jagentharness.runner;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Metadata embedded by the build, independent of the machine running the JAR. */
final class RunnerBuild {
    private final Properties values = new Properties();

    RunnerBuild() throws IOException {
        try (InputStream input =
                RunnerBuild.class.getResourceAsStream("/coding-runner-build.properties")) {
            if (input != null) values.load(input);
        }
    }

    String value(String key) {
        String value = values.getProperty(key, "").trim();
        return value.isEmpty() || value.contains("${") || "unknown".equals(value) ? null : value;
    }
}

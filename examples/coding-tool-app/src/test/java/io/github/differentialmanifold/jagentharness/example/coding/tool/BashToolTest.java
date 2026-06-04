package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashToolTest {

    @TempDir
    Path tempDir;

    @Test
    void usesGitBashOnWindows() throws Exception {
        Path gitBin = tempDir.resolve("Git").resolve("bin");
        Files.createDirectories(gitBin);
        Path gitBash = gitBin.resolve("bash.exe");
        Files.write(gitBash, new byte[0]);

        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", gitBin.toString());

        assertEquals(Arrays.asList(gitBash.toString(), "-lc", "pwd"),
                BashTool.shellCommand("pwd", "Windows 11", environment));
    }

    @Test
    void requiresGitBashOnWindows() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> BashTool.shellCommand("dir", "Windows 11", Collections.emptyMap()));

        assertTrue(exception.getMessage().contains("Git Bash is required"));
    }

    @Test
    void usesShOnUnixLikeSystems() {
        assertEquals(Arrays.asList("/bin/sh", "-lc", "ls"),
                BashTool.shellCommand("ls", "Mac OS X", Collections.emptyMap()));
    }
}

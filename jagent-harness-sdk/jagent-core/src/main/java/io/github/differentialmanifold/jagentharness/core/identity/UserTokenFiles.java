package io.github.differentialmanifold.jagentharness.core.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Explicit credential output is owner-readable; secrets never appear in ordinary logs. */
public final class UserTokenFiles {
    private UserTokenFiles() {}

    public static void write(Path file, String id, String token) throws IOException {
        writePrivate(file, "# JAgentHarness user: " + id + "\nJAGENT_USER_TOKEN=" + token + "\n");
    }

    public static void writePrivate(Path file, String content) throws IOException {
        file = file.toAbsolutePath();
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".user-", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void bootstrap(UserStore users, Path file) throws IOException {
        if (Files.exists(file)) return;
        // Missing credentials must never silently rotate an existing user's identity.
        if (users.get(UserContext.LOCAL) != null) return;
        String token = users.initializeLocal();
        if (token != null) write(file, UserContext.LOCAL, token);
    }
}

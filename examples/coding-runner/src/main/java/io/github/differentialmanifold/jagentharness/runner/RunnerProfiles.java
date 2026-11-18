package io.github.differentialmanifold.jagentharness.runner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Per-OS-user index of registered CLI runners. Credentials remain in their original file or
 * environment.
 */
final class RunnerProfiles {
    private final Path directory;

    RunnerProfiles(Path state) {
        directory = state.resolve("profiles");
    }

    void remember(String server, String console, Path home, String user, Path credentials)
            throws IOException {
        Properties profile = new Properties();
        profile.setProperty("server", server);
        profile.setProperty("console", console);
        profile.setProperty("home", home.toAbsolutePath().normalize().toString());
        profile.setProperty("user", user);
        if (credentials != null)
            profile.setProperty("credentials", credentials.toAbsolutePath().normalize().toString());
        String key =
                UUID.nameUUIDFromBytes(
                                (server + "\n" + user + "\n" + profile.getProperty("home"))
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .substring(0, 12);
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".profile-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                profile.store(out, "JAgent Runner profile");
            }
            Path target = directory.resolve(key + ".properties");
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    Properties select(String key, String server, String home) throws IOException {
        Map<String, Properties> matches = new TreeMap<>();
        if (Files.isDirectory(directory))
            try (DirectoryStream<Path> entries =
                    Files.newDirectoryStream(directory, "*.properties")) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString().replace(".properties", "");
                    if (key != null && !key.equals(name)) continue;
                    Properties profile = new Properties();
                    try (InputStream in = Files.newInputStream(entry)) {
                        profile.load(in);
                    }
                    if (server != null && !server.equals(profile.getProperty("server"))) continue;
                    if (home != null
                            && !Paths.get(home)
                                    .toAbsolutePath()
                                    .normalize()
                                    .toString()
                                    .equals(profile.getProperty("home"))) continue;
                    matches.put(name, profile);
                }
            }
        if (matches.isEmpty() && key != null)
            throw new IllegalArgumentException("No matching Runner profile: " + key);
        if (matches.size() > 1) {
            StringBuilder choices = new StringBuilder("Choose a Runner with --profile <id>:");
            matches.forEach(
                    (id, p) ->
                            choices.append("\n  ")
                                    .append(id)
                                    .append("  ")
                                    .append(p.getProperty("server"))
                                    .append("  ")
                                    .append(p.getProperty("user"))
                                    .append("  ")
                                    .append(p.getProperty("home")));
            throw new IllegalArgumentException(choices.toString());
        }
        return matches.isEmpty() ? new Properties() : matches.values().iterator().next();
    }
}

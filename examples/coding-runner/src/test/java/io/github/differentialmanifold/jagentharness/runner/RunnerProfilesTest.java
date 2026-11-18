package io.github.differentialmanifold.jagentharness.runner;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunnerProfilesTest {
    @TempDir Path state;

    @Test
    void multipleProfilesRequireSelectionAndKeepOnlyCredentialReferences() throws Exception {
        RunnerProfiles profiles = new RunnerProfiles(state);
        Path credential = state.resolve("user.env");
        Files.write(credential, "JAGENT_USER_TOKEN=private-token".getBytes("UTF-8"));
        String first = "https://first.example", second = "https://second.example";
        profiles.remember(first, first, state, "alice", credential);
        profiles.remember(first, first, state, "alice", credential);
        Properties one = profiles.select(null, null, null);
        assertEquals(credential.toString(), one.getProperty("credentials"));
        assertFalse(one.toString().contains("private-token"));
        profiles.remember(second, second, state, "bob", null);
        assertThrows(IllegalArgumentException.class, () -> profiles.select(null, null, null));
        String id;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(state.resolve("profiles"))) {
            id = files.iterator().next().getFileName().toString().replace(".properties", "");
        }
        Properties selected = profiles.select(id, null, null);
        assertEquals(selected, profiles.select(null, selected.getProperty("server"), null));
        assertNull(profiles.select(null, second, null).getProperty("credentials"));
        assertThrows(IllegalArgumentException.class, () -> profiles.select("missing", null, null));
    }
}

package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class JdbcPrimaryAgentTest {
    @TempDir Path temp;

    @Test
    void primarySelectionSurvivesRestartAndDoesNotCrossApplicationsOrOverwriteConcurrentChanges() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("primary.db"));
        new JdbcSchemaInitializer(ds).initialize();
        JdbcTemplate db = new JdbcTemplate(ds);
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId("one");
        JdbcPersonalAgentStore store =
                new JdbcPersonalAgentStore(db, new ObjectMapper(), properties);
        assertNull(store.primaryAgentId());
        assertTrue(store.setPrimaryAgentId("first", null));
        assertFalse(store.setPrimaryAgentId("second", null));
        JdbcPersonalAgentStore restored =
                new JdbcPersonalAgentStore(db, new ObjectMapper(), properties);
        assertEquals("first", restored.primaryAgentId());
        assertTrue(restored.setPrimaryAgentId("second", "first"));
        assertFalse(store.setPrimaryAgentId("stale", "first"));
        assertEquals("second", store.primaryAgentId());
        properties.setApplicationId("two");
        JdbcPersonalAgentStore other =
                new JdbcPersonalAgentStore(db, new ObjectMapper(), properties);
        assertNull(other.primaryAgentId());
        assertTrue(other.setPrimaryAgentId("different", null));
        assertEquals("second", store.primaryAgentId());
    }

    @Test
    void mainbotBootstrapIsAtomicAndSharedAcrossServiceInstances() throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("mainbot.db"));
        ds.setBusyTimeout(5000);
        new JdbcSchemaInitializer(ds).initialize();
        JdbcTemplate db = new JdbcTemplate(ds);
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId("bootstrap");
        java.util.concurrent.ExecutorService workers =
                java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.atomic.AtomicInteger created =
                new java.util.concurrent.atomic.AtomicInteger();
        try {
            java.util.List<java.util.concurrent.Callable<Boolean>> calls =
                    new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++)
                calls.add(
                        () -> {
                            JdbcPersonalAgentStore store =
                                    new JdbcPersonalAgentStore(db, new ObjectMapper(), properties);
                            io.github.differentialmanifold.jagentharness.core.personal
                                            .PersonalAgentStore.Profile
                                    profile =
                                            new io.github.differentialmanifold.jagentharness.core
                                                    .personal.PersonalAgentStore.Profile();
                            profile.id = "agent_mainbot";
                            profile.name = "mainbot";
                            return store.initializeMainbot(profile, created::incrementAndGet);
                        });
            int winners = 0;
            for (java.util.concurrent.Future<Boolean> result : workers.invokeAll(calls))
                if (result.get()) winners++;
            assertEquals(1, winners);
            assertEquals(1, created.get());
        } finally {
            workers.shutdownNow();
        }
        JdbcPersonalAgentStore store =
                new JdbcPersonalAgentStore(db, new ObjectMapper(), properties);
        io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.Profile
                failed =
                        new io.github.differentialmanifold.jagentharness.core.personal
                                .PersonalAgentStore.Profile();
        failed.id = "agent_failed";
        failed.name = "mainbot";
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.initializeMainbot(
                                failed,
                                () -> {
                                    throw new IllegalStateException("conversation creation failed");
                                }));
        assertNull(store.profile(failed.id));
        assertEquals(1, store.profiles().size());
    }
}

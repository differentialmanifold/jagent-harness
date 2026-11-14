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
}

package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcSessionRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsProjectNameWhenCreatingSession() {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId("default");
        JdbcSessionRepository repository = new JdbcSessionRepository(jdbcTemplate, properties);

        SessionRecord created = repository.create(
                "New Chat - Demo",
                "/tmp/demo",
                "Demo");

        SessionRecord loaded = repository.findBySessionId(created.getSessionId());
        assertEquals("New Chat - Demo", loaded.getTitle());
        assertEquals("Demo", loaded.getProjectName());
        assertEquals("/tmp/demo", loaded.getWorkspacePath());
    }

    private JdbcTemplate createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("sessions.db"));
        new JdbcSchemaInitializer(dataSource).initialize();
        return new JdbcTemplate(dataSource);
    }
}

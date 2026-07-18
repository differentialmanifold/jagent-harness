package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTimelineEventRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsRunAndTurnIdentity() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("timeline.db"));
        new JdbcSchemaInitializer(dataSource).initialize();
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId("default");
        JdbcTimelineEventRepository repository = new JdbcTimelineEventRepository(
                new JdbcTemplate(dataSource), properties);

        repository.append(AgentEvent.of(
                "session-1", "run-1", "turn-1", AgentEvent.TURN_END, "{}"));

        List<AgentEvent> events = repository.findBySessionId("session-1");
        assertEquals(1, events.size());
        assertEquals("run-1", events.get(0).getRunId());
        assertEquals("turn-1", events.get(0).getTurnId());
    }
}

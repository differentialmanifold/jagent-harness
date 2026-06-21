package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcMessageRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsReasoningContent() {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId("default");
        JdbcMessageRepository repository = new JdbcMessageRepository(
                jdbcTemplate,
                new ObjectMapper(),
                properties);

        AgentMessage message = AgentMessage.assistant("session-1", "final answer", Collections.emptyList());
        message.setReasoningContent("think first");
        repository.append(message);

        List<AgentMessage> messages = repository.findBySessionId("session-1");
        assertEquals(1, messages.size());
        assertEquals("final answer", messages.get(0).getContent());
        assertEquals("think first", messages.get(0).getReasoningContent());
    }

    private JdbcTemplate createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("messages.db"));
        new JdbcSchemaInitializer(dataSource).initialize();
        return new JdbcTemplate(dataSource);
    }
}

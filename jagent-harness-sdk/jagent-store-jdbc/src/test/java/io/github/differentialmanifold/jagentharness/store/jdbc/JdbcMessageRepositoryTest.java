package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
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
        message.setRunId("run-1");
        message.setTurnId("turn-1");
        message.setReasoningContent("think first");
        repository.append(message);

        List<AgentMessage> messages = repository.findBySessionId("session-1");
        assertEquals(1, messages.size());
        assertEquals("final answer", messages.get(0).getContent());
        assertEquals("think first", messages.get(0).getReasoningContent());
        assertEquals("run-1", messages.get(0).getRunId());
        assertEquals("turn-1", messages.get(0).getTurnId());
    }

    @Test
    void persistsMessageImagesInOrder() {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId("default");
        JdbcMessageRepository repository = new JdbcMessageRepository(
                jdbcTemplate,
                new ObjectMapper(),
                properties);

        AgentMessage message = AgentMessage.user("session-1", "compare these images");
        message.setRunId("run-1");
        message.setTurnId("turn-1");
        message.setImages(Arrays.asList(
                image("first.png", "image/png", "data:image/png;base64,Zmlyc3Q=", "low"),
                image("second.jpg", "image/jpeg", "data:image/jpeg;base64,c2Vjb25k", null)));
        repository.append(message);

        List<AgentMessage> messages = repository.findBySessionId("session-1");
        assertEquals(1, messages.size());
        assertEquals(2, messages.get(0).getImages().size());
        assertImage(
                messages.get(0).getImages().get(0),
                "first.png",
                "image/png",
                "data:image/png;base64,Zmlyc3Q=",
                "low");
        assertImage(
                messages.get(0).getImages().get(1),
                "second.jpg",
                "image/jpeg",
                "data:image/jpeg;base64,c2Vjb25k",
                null);
    }

    private MessageImage image(String name, String mediaType, String url, String detail) {
        MessageImage image = new MessageImage();
        image.setName(name);
        image.setMediaType(mediaType);
        image.setUrl(url);
        image.setDetail(detail);
        return image;
    }

    private void assertImage(MessageImage image,
                             String name,
                             String mediaType,
                             String url,
                             String detail) {
        assertEquals(name, image.getName());
        assertEquals(mediaType, image.getMediaType());
        assertEquals(url, image.getUrl());
        assertEquals(detail, image.getDetail());
    }

    private JdbcTemplate createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("messages.db"));
        new JdbcSchemaInitializer(dataSource).initialize();
        return new JdbcTemplate(dataSource);
    }
}

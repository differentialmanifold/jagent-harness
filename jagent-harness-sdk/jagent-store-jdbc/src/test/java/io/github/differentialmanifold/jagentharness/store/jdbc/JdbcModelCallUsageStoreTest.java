package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;

import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcModelCallUsageStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsAndFindsLatestUsageForSession() {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId("default");
        JdbcModelCallUsageStore store = new JdbcModelCallUsageStore(jdbcTemplate, properties);

        store.append(usage("usage-1", "session-1", "message-1", 100));
        store.append(usage("usage-2", "session-1", "message-2", 140));
        store.append(usage("usage-3", "session-2", "message-3", 200));

        ModelCallUsage latest = store.findLatestBySessionId("session-1");

        assertEquals("usage-2", latest.getUsageId());
        assertEquals("run-1", latest.getRunId());
        assertEquals("turn-1", latest.getTurnId());
        assertEquals("message-2", latest.getMessageId());
        assertEquals(Integer.valueOf(140), latest.getActualContextTokens());
        assertEquals(Integer.valueOf(150), latest.getTotalTokens());
        assertEquals(Integer.valueOf(10), latest.getReasoningTokens());
    }

    private ModelCallUsage usage(String usageId, String sessionId, String messageId, int actualContextTokens) {
        ModelCallUsage usage = new ModelCallUsage();
        usage.setUsageId(usageId);
        usage.setSessionId(sessionId);
        usage.setRunId("run-1");
        usage.setTurnId("turn-1");
        usage.setMessageId(messageId);
        usage.setProvider("provider");
        usage.setModel("model");
        usage.setContextWindowTokens(128000);
        usage.setThresholdTokens(102400);
        usage.setEstimateSource(ModelCallUsage.ESTIMATE_SOURCE_FULL);
        usage.setEstimatedTokens(120);
        usage.setActualContextTokens(actualContextTokens);
        usage.setPromptTokens(80);
        usage.setCompletionTokens(70);
        usage.setReasoningTokens(10);
        usage.setCachedTokens(0);
        usage.setTotalTokens(actualContextTokens + 10);
        usage.setCreatedAt(Instant.now());
        return usage;
    }

    private JdbcTemplate createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("usage.db"));
        new JdbcSchemaInitializer(dataSource).initialize();
        return new JdbcTemplate(dataSource);
    }
}

package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.Inbox;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcPersonalInboxTest {
    @TempDir Path temp;

    @Test
    void concurrentAndRepeatedEnqueuesPreserveOneReceiptAndTransactions() throws Exception {
        for (String url :
                Arrays.asList(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "jdbc:sqlite:" + temp.resolve("inbox.db"))) {
            DriverManagerDataSource source = new DriverManagerDataSource(url);
            new JdbcSchemaInitializer(source).initialize();
            AtomicInteger errors = new AtomicInteger();
            JdbcTemplate db =
                    new JdbcTemplate(source) {
                        @Override
                        public int update(String sql, Object... args) {
                            try {
                                return super.update(sql, args);
                            } catch (DataAccessException error) {
                                errors.incrementAndGet();
                                throw error;
                            }
                        }
                    };
            JdbcStoreProperties properties = new JdbcStoreProperties();
            properties.setApplicationId("one");
            ExecutorService threads = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    final int attempt = i;
                    results.add(
                            threads.submit(
                                    () -> {
                                        start.await();
                                        return new JdbcPersonalAgentStore(
                                                        db, new ObjectMapper(), properties)
                                                .enqueue(message("same", "attempt-" + attempt));
                                    }));
                }
                start.countDown();
                int accepted = 0;
                for (Future<Boolean> result : results)
                    if (result.get(10, TimeUnit.SECONDS)) accepted++;
                assertEquals(1, accepted);
                if (url.startsWith("jdbc:sqlite:")) assertEquals(0, errors.get());
                JdbcPersonalAgentStore store =
                        new JdbcPersonalAgentStore(db, new ObjectMapper(), properties);
                Inbox original = store.message("same");
                original.status = "DONE";
                store.update(original, "PENDING");
                int before = errors.get();
                JdbcTransactions.write(db)
                        .execute(
                                status -> {
                                    for (int i = 0; i < 20; i++)
                                        assertFalse(store.enqueue(message("same", "replacement")));
                                    assertTrue(store.enqueue(message("next", "new revision")));
                                    return null;
                                });
                assertEquals(
                        before,
                        errors.get(),
                        "Repeated duplicates must not execute failing INSERTs");
                assertEquals(original.text, store.message("same").text);
                assertEquals("DONE", store.message("same").status);
                assertEquals(2, store.messages("chat").size());
                assertNotNull(
                        new JdbcPersonalAgentStore(db, new ObjectMapper(), properties)
                                .message("next"));
                JdbcStoreProperties other = new JdbcStoreProperties();
                other.setApplicationId("other");
                assertTrue(
                        new JdbcPersonalAgentStore(db, new ObjectMapper(), other)
                                .enqueue(message("same", "isolated")));
                Inbox invalid = message("invalid", "Invalid status");
                invalid.status = null;
                assertThrows(DataAccessException.class, () -> store.enqueue(invalid));
            } finally {
                start.countDown();
                threads.shutdownNow();
            }
        }
    }

    @Test
    void postgresUsesAnAtomicConflictTargetInsteadOfRaisingDuplicateErrors() throws Exception {
        javax.sql.DataSource source = mock(javax.sql.DataSource.class);
        java.sql.Connection connection = mock(java.sql.Connection.class);
        java.sql.DatabaseMetaData metadata = mock(java.sql.DatabaseMetaData.class);
        java.sql.PreparedStatement statement = mock(java.sql.PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        String sql =
                "insert into personal_inbox(application_id, message_id, session_id, status, created_at, body_json) values(?,?,?,?,?,?) on conflict(application_id, message_id) do nothing";
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1, 0);
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId("postgres");
        JdbcPersonalAgentStore store =
                new JdbcPersonalAgentStore(
                        new JdbcTemplate(source), new ObjectMapper(), properties);
        assertTrue(store.enqueue(message("same", "original")));
        assertFalse(store.enqueue(message("same", "retry")));
        verify(connection, times(2)).prepareStatement(sql);
    }

    private Inbox message(String id, String text) {
        Inbox m = new Inbox();
        m.id = id;
        m.agentId = "agent";
        m.sessionId = "chat";
        m.source = "CONTINUE";
        m.text = text;
        return m;
    }
}

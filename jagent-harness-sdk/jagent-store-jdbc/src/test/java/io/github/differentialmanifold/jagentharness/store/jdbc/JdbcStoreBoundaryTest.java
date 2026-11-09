package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcStoreBoundaryTest {
    @TempDir Path directory;

    private JdbcTemplate database(String dialect) {
        DataSource source =
                new DriverManagerDataSource(
                        dialect.equals("h2")
                                ? "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"
                                : "jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + ".db"));
        new JdbcSchemaInitializer(source).initialize();
        return new JdbcTemplate(source);
    }

    private String text(int size) {
        char[] chars = new char[size];
        Arrays.fill(chars, 'x');
        return new String(chars);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "h2"})
    void rejectsOversizedFieldsBeforeAnyWrite(String dialect) {
        JdbcTemplate db = database(dialect);
        JdbcSessionRepository sessions = new JdbcSessionRepository(db, new JdbcStoreProperties());
        assertThrows(
                IllegalArgumentException.class,
                () -> sessions.create(text(513), "/work", "ok", "p"));
        assertThrows(
                IllegalArgumentException.class,
                () -> sessions.create("ok", "/work", text(513), "p"));
        assertThrows(
                IllegalArgumentException.class, () -> sessions.create("ok", text(1025), "ok", "p"));
        assertEquals(0, db.queryForObject("select count(*) from projects", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from sessions", Integer.class));
        String id = sessions.create(text(512), "/work", "ok", "p").getSessionId();
        assertThrows(IllegalArgumentException.class, () -> sessions.updateTitle(id, text(513)));
        assertEquals(text(512), sessions.findBySessionId(id).getTitle());

        JdbcKnowledgeFileStore files = new JdbcKnowledgeFileStore(db, new JdbcStoreProperties());
        assertThrows(
                IllegalArgumentException.class,
                () -> files.writeFile("dir/" + text(257), "x", null));
        assertThrows(
                IllegalArgumentException.class, () -> files.writeFile("ok.md", "x", text(129)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        files.writeFile(
                                "skills/test/SKILL.md",
                                "---\nname: " + text(257) + "\n---\nx",
                                null));
        assertEquals(0, db.queryForObject("select count(*) from knowledge_files", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from skill_manifests", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "h2"})
    void deletingTreesLeavesNoUnusedDirectoryRowsOrSkillManifests(String dialect) {
        JdbcTemplate db = database(dialect);
        JdbcKnowledgeFileStore files = new JdbcKnowledgeFileStore(db, new JdbcStoreProperties());
        KnowledgeScope project = KnowledgeScope.project("p");
        files.writeFile(project, "skills/sample/SKILL.md", "---\nname: Sample\n---\nHello", null);
        files.writeFile("a/b/keep.md", "keep", null);
        for (int i = 0; i < 20; i++) {
            String path = "a/b/" + i + "/file.md";
            files.writeFile(path, "test", null);
            files.deleteFile(path);
        }
        files.deleteFile(project, "skills/sample/SKILL.md");
        assertEquals(1, db.queryForObject("select count(*) from knowledge_files", Integer.class));
        assertEquals("keep", files.readFile("a/b/keep.md").getContent());
        assertEquals(0, db.queryForObject("select count(*) from skill_manifests", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "h2"})
    void searchUsesConsistentUnicodeCaseAndLiteralSubstrings(String dialect) {
        JdbcTemplate db = database(dialect);
        JdbcMessageRepository messages =
                new JdbcMessageRepository(db, new ObjectMapper(), new JdbcStoreProperties());
        for (String content :
                Arrays.asList("ÄPFEL", "CAFÉ", "İstanbul", "中文", "literal %_!", "plain")) {
            AgentMessage message = AgentMessage.user("s", content);
            message.setRunId("r");
            message.setTurnId("t");
            messages.append(message);
        }
        List<AgentMessage> noise = new ArrayList<>();
        for (int i = 0; i < 270; i++) {
            AgentMessage message = AgentMessage.user("s", "nonmatching");
            message.setRunId("r");
            message.setTurnId("t");
            noise.add(message);
        }
        messages.appendAll(noise);
        for (String query : Arrays.asList("äpfel", "café", "İSTANBUL", "中文", "%_!")) {
            assertEquals(
                    1, messages.search(Collections.singletonList("s"), query, 10).size(), query);
            assertTrue(messages.search(Collections.singletonList("other"), query, 10).isEmpty());
        }
        assertEquals(2, messages.search(Collections.singletonList("s"), "", 2).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "h2"})
    void closeSerializesWithInFlightSubmissionAcrossInstances(String dialect) throws Exception {
        JdbcTemplate db = database(dialect);
        CountDownLatch inserting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JdbcTemplate paused =
                new JdbcTemplate(db.getDataSource()) {
                    @Override
                    public int update(String sql, Object... args) {
                        if (sql.startsWith("insert into agent_run_inputs")) {
                            inserting.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("release timeout");
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                        }
                        return super.update(sql, args);
                    }
                };
        JdbcRunInputCoordinator writer =
                new JdbcRunInputCoordinator(paused, new JdbcStoreProperties());
        JdbcRunInputCoordinator closer = new JdbcRunInputCoordinator(db, new JdbcStoreProperties());
        writer.activateRun("s", "r");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> submit = executor.submit(() -> writer.submitInput("r", "input", "i"));
            assertTrue(inserting.await(5, TimeUnit.SECONDS));
            CountDownLatch closing = new CountDownLatch(1);
            Future<?> close =
                    executor.submit(
                            () -> {
                                closing.countDown();
                                closer.closeRun("s", "r");
                            });
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            submit.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            assertEquals(
                    "CANCELLED",
                    db.queryForObject(
                            "select status from agent_run_inputs where input_id='i'",
                            String.class));
            assertTrue(closer.claimPendingInputs("s", "r", "t").isEmpty());
            assertEquals(
                    0, db.queryForObject("select count(*) from agent_active_runs", Integer.class));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sqliteSingleConnectionPoolSerializesConcurrentOwnerAssignments() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + directory.resolve("pool.db"));
        config.setMaximumPoolSize(
                1); // server-demo default: DB transactions, not sessions, are queued.
        try (HikariDataSource source = new HikariDataSource(config)) {
            new JdbcSchemaInitializer(source).initialize();
            JdbcSessionRepository sessions =
                    new JdbcSessionRepository(new JdbcTemplate(source), new JdbcStoreProperties());
            sessions.create("task", "/work", "project", "p");
            ExecutorService executor = Executors.newFixedThreadPool(16);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<Boolean>> futures = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    final String owner = "agent-" + i;
                    futures.add(
                            executor.submit(
                                    () -> {
                                        start.await();
                                        return sessions.assignProjectOwnerIfUnowned("p", owner);
                                    }));
                }
                start.countDown();
                int winners = 0;
                for (Future<Boolean> future : futures)
                    if (future.get(10, TimeUnit.SECONDS)) winners++;
                assertEquals(1, winners);
            } finally {
                executor.shutdownNow();
            }
        }
    }
}

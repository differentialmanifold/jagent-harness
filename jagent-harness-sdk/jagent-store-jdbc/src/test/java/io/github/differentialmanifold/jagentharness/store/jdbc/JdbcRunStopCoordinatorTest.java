package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.differentialmanifold.jagentharness.core.agent.ActiveRunException;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopHandle;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcRunStopCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void propagatesStopBetweenInstancesForOneRequest() throws Exception {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcRunStopCoordinator owner = coordinator(jdbcTemplate);
        JdbcRunStopCoordinator apiInstance = coordinator(jdbcTemplate);
        RunStopHandle first = owner.register("request-1", "session-1");
        RunStopHandle second = owner.register("request-2", "session-1");
        CountDownLatch firstStopped = new CountDownLatch(1);
        CountDownLatch secondStopped = new CountDownLatch(1);
        first.onStop(firstStopped::countDown);
        second.onStop(secondStopped::countDown);

        try {
            assertEquals(StopRequestResult.REQUESTED, apiInstance.requestStop("request-1"));
            assertTrue(firstStopped.await(2, TimeUnit.SECONDS));
            assertTrue(first.isAborted());
            assertFalse(secondStopped.await(500, TimeUnit.MILLISECONDS));
            assertFalse(second.isAborted());
            assertEquals(StopRequestResult.ALREADY_REQUESTED, apiInstance.requestStop("request-1"));
        } finally {
            first.close();
            second.close();
            owner.close();
            apiInstance.close();
        }

        assertEquals(StopRequestResult.ALREADY_REQUESTED, coordinatorResult(jdbcTemplate, "request-1"));
        assertEquals(
                "STOP_REQUESTED",
                jdbcTemplate.queryForObject(
                        "select status from agent_runs where request_id = ?",
                        String.class,
                        "request-1"));
    }

    @Test
    void rejectsDuplicateRequestIdAndRetainsNormalRecordOnClose() {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcRunStopCoordinator firstCoordinator = coordinator(jdbcTemplate);
        JdbcRunStopCoordinator secondCoordinator = coordinator(jdbcTemplate);
        RunStopHandle handle = firstCoordinator.register("request-1", "session-1");

        try {
            assertTrue(
                    jdbcTemplate.queryForObject(
                            "select id from agent_runs where request_id = ?",
                            Long.class,
                            "request-1") > 0L);
            assertThrows(
                    ActiveRunException.class,
                    () -> secondCoordinator.register("request-1", "session-2"));
            handle.close();
            assertEquals(
                    "NORMAL",
                    jdbcTemplate.queryForObject(
                            "select status from agent_runs where request_id = ?",
                            String.class,
                            "request-1"));
        } finally {
            handle.close();
            firstCoordinator.close();
            secondCoordinator.close();
        }
    }

    private JdbcTemplate createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("stop-coordinator.db"));
        new JdbcSchemaInitializer(dataSource).initialize();
        return new JdbcTemplate(dataSource);
    }

    private JdbcRunStopCoordinator coordinator(JdbcTemplate jdbcTemplate) {
        JdbcRunStopProperties properties = new JdbcRunStopProperties();
        properties.setPollIntervalMillis(20L);
        properties.setListenerThreads(1);
        return new JdbcRunStopCoordinator(jdbcTemplate, properties);
    }

    private StopRequestResult coordinatorResult(JdbcTemplate jdbcTemplate, String requestId) {
        JdbcRunStopCoordinator coordinator = coordinator(jdbcTemplate);
        try {
            return coordinator.requestStop(requestId);
        } finally {
            coordinator.close();
        }
    }
}

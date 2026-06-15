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
        JdbcRunStopCoordinator owner = coordinator(jdbcTemplate, 200L);
        JdbcRunStopCoordinator apiInstance = coordinator(jdbcTemplate, 200L);
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

        assertEquals(StopRequestResult.NOT_FOUND, coordinatorResult(jdbcTemplate, "request-1"));
    }

    @Test
    void rejectsDuplicateActiveRequestAndAllowsExpiredRequestToBeReclaimed() {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcRunStopCoordinator firstCoordinator = coordinator(jdbcTemplate);
        JdbcRunStopCoordinator secondCoordinator = coordinator(jdbcTemplate);
        RunStopHandle handle = firstCoordinator.register("request-1", "session-1");

        try {
            assertThrows(
                    ActiveRunException.class,
                    () -> secondCoordinator.register("request-1", "session-2"));
            handle.close();

            long now = System.currentTimeMillis();
            jdbcTemplate.update(
                    "insert into agent_runs "
                            + "(request_id, session_id, owner_instance_id, status, lease_until, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?)",
                    "expired-request",
                    "session-1",
                    "dead-instance",
                    "RUNNING",
                    now - 1,
                    now - 1000,
                    now - 1000);
            RunStopHandle reclaimed = secondCoordinator.register("expired-request", "session-2");
            reclaimed.close();
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
        return coordinator(jdbcTemplate, 1000L);
    }

    private JdbcRunStopCoordinator coordinator(JdbcTemplate jdbcTemplate, long leaseDurationMillis) {
        JdbcRunStopProperties properties = new JdbcRunStopProperties();
        properties.setPollIntervalMillis(20L);
        properties.setLeaseDurationMillis(leaseDurationMillis);
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

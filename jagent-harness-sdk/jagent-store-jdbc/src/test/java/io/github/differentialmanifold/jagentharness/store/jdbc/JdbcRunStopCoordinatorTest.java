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
    void propagatesStopBetweenInstancesForOneRun() throws Exception {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcRunStopCoordinator owner = coordinator(jdbcTemplate);
        JdbcRunStopCoordinator apiInstance = coordinator(jdbcTemplate);
        RunStopHandle first = owner.register("run-1", "session-1");
        RunStopHandle second = owner.register("run-2", "session-1");
        CountDownLatch firstStopped = new CountDownLatch(1);
        CountDownLatch secondStopped = new CountDownLatch(1);
        first.onStop(firstStopped::countDown);
        second.onStop(secondStopped::countDown);

        try {
            assertEquals(StopRequestResult.REQUESTED, apiInstance.requestStop("run-1"));
            assertTrue(firstStopped.await(2, TimeUnit.SECONDS));
            assertTrue(first.isAborted());
            assertFalse(secondStopped.await(500, TimeUnit.MILLISECONDS));
            assertFalse(second.isAborted());
            assertEquals(StopRequestResult.ALREADY_REQUESTED, apiInstance.requestStop("run-1"));
        } finally {
            first.close();
            second.close();
            owner.close();
            apiInstance.close();
        }

        assertEquals(StopRequestResult.ALREADY_REQUESTED, coordinatorResult(jdbcTemplate, "run-1"));
        assertEquals(
                "STOP_REQUESTED",
                jdbcTemplate.queryForObject(
                        "select status from agent_runs where application_id = ? and run_id = ?",
                        String.class,
                        "default",
                        "run-1"));
    }

    @Test
    void rejectsDuplicateRunIdAndRetainsNormalRecordOnClose() {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcRunStopCoordinator firstCoordinator = coordinator(jdbcTemplate);
        JdbcRunStopCoordinator secondCoordinator = coordinator(jdbcTemplate);
        RunStopHandle handle = firstCoordinator.register("run-1", "session-1");

        try {
            assertTrue(
                    jdbcTemplate.queryForObject(
                            "select id from agent_runs where application_id = ? and run_id = ?",
                            Long.class,
                            "default",
                            "run-1") > 0L);
            assertThrows(
                    ActiveRunException.class,
                    () -> secondCoordinator.register("run-1", "session-2"));
            handle.close();
            assertEquals(
                    "NORMAL",
                    jdbcTemplate.queryForObject(
                            "select status from agent_runs where application_id = ? and run_id = ?",
                            String.class,
                            "default",
                            "run-1"));
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
        return new JdbcRunStopCoordinator(jdbcTemplate, storeProperties("default"), properties);
    }

    private StopRequestResult coordinatorResult(JdbcTemplate jdbcTemplate, String runId) {
        JdbcRunStopCoordinator coordinator = coordinator(jdbcTemplate);
        try {
            return coordinator.requestStop(runId);
        } finally {
            coordinator.close();
        }
    }

    private JdbcStoreProperties storeProperties(String applicationId) {
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId(applicationId);
        return properties;
    }
}

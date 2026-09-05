package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.RunInput;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputReceipt;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputStatus;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcRunInputCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void drainsAllPendingInputsInInsertionOrderAsOneBatch() {
        JdbcTemplate jdbcTemplate = createDatabase("pi-batches.db");
        JdbcRunInputCoordinator inputs = inputCoordinator(jdbcTemplate);
        inputs.activateRun("session-1", "run-1");

        inputs.submitInput("run-1", "first", "input-1");
        inputs.submitInput("run-1", "second", "input-2");
        inputs.submitInput("run-1", "third", "input-3");

        List<RunInput> batch = inputs.claimPendingInputs(
                "session-1", "run-1", "turn-1");
        assertInputIds(batch, "input-1", "input-2", "input-3");
        assertStatuses(
                batch,
                RunInputStatus.CLAIMED,
                RunInputStatus.CLAIMED,
                RunInputStatus.CLAIMED);

        // A boundary is not replayed: only still-pending rows are returned.
        assertTrue(inputs.claimPendingInputs(
                "session-1", "run-1", "turn-1").isEmpty());
        assertEquals(1, acceptingInputs(jdbcTemplate, "run-1"));
    }

    @Test
    void onlyClaimsInputsForTheRequestedSessionAndRun() {
        JdbcTemplate jdbcTemplate = createDatabase("run-scope.db");
        JdbcRunInputCoordinator inputs = inputCoordinator(jdbcTemplate);
        inputs.activateRun("session-1", "run-1");
        inputs.activateRun("session-2", "run-2");
        inputs.submitInput("run-1", "first", "input-1");
        inputs.submitInput("run-2", "second", "input-2");

        assertTrue(inputs.claimPendingInputs(
                "session-2", "run-1", "turn-1").isEmpty());
        assertInputIds(inputs.claimPendingInputs(
                "session-1", "run-1", "turn-1"), "input-1");
        assertInputIds(inputs.claimPendingInputs(
                "session-2", "run-2", "turn-1"), "input-2");
    }

    @Test
    void submissionIsIdempotentButInputIdCannotChangeRunOrContent() {
        JdbcTemplate jdbcTemplate = createDatabase("idempotency.db");
        JdbcRunInputCoordinator inputs = inputCoordinator(jdbcTemplate);
        inputs.activateRun("session-1", "run-1");

        inputs.submitInput("run-1", "same", "input-1");
        RunInputReceipt retry = inputs.submitInput("run-1", "same", "input-1");

        assertEquals(RunInputStatus.ACCEPTED, retry.getStatus());
        assertThrows(
                IllegalArgumentException.class,
                () -> inputs.submitInput("run-2", "same", "input-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> inputs.submitInput("run-1", "changed", "input-1"));

        inputs.claimPendingInputs("session-1", "run-1", "turn-1");
        RunInputReceipt claimedRetry = inputs.submitInput("run-1", "same", "input-1");
        assertEquals(RunInputStatus.CLAIMED, claimedRetry.getStatus());
    }

    @Test
    void persistsImagesForClaimedInputsAndIncludesThemInIdempotency() {
        JdbcTemplate jdbcTemplate = createDatabase("image-input.db");
        JdbcRunInputCoordinator inputs = inputCoordinator(jdbcTemplate);
        inputs.activateRun("session-1", "run-1");
        List<MessageImage> images = java.util.Collections.singletonList(
                image("screen.png", "image/png", "data:image/png;base64,c2NyZWVu", "high"));

        inputs.submitInput("run-1", "", images, "input-1");
        RunInputReceipt retry = inputs.submitInput(
                "run-1",
                "",
                java.util.Collections.singletonList(
                        image("screen.png", "image/png", "data:image/png;base64,c2NyZWVu", "high")),
                "input-1");

        assertEquals(RunInputStatus.ACCEPTED, retry.getStatus());
        List<RunInput> claimed = inputs.claimPendingInputs(
                "session-1", "run-1", "turn-1");
        assertEquals(1, claimed.size());
        assertEquals("", claimed.get(0).getContent());
        assertEquals(1, claimed.get(0).getImages().size());
        assertImage(claimed.get(0).getImages().get(0), images.get(0));

        assertThrows(
                IllegalArgumentException.class,
                () -> inputs.submitInput(
                        "run-1",
                        "",
                        java.util.Collections.singletonList(
                                image("other.png", "image/png", "data:image/png;base64,b3RoZXI=", "high")),
                        "input-1"));
    }

    @Test
    void lateSubmissionIsAcknowledgedButNotStoredOrExecuted() {
        JdbcTemplate jdbcTemplate = createDatabase("late-best-effort.db");
        JdbcRunInputCoordinator inputs = inputCoordinator(jdbcTemplate);
        inputs.activateRun("session-1", "run-1");
        inputs.closeRun("session-1", "run-1");

        RunInputReceipt receipt = inputs.submitInput("run-1", "too late", "input-late");

        assertEquals(RunInputStatus.ACCEPTED, receipt.getStatus());
        assertEquals(0, count(jdbcTemplate, "agent_run_inputs"));
        assertTrue(inputs.claimPendingInputs(
                "session-1", "run-1", "turn-1").isEmpty());
    }

    @Test
    void allowsMultipleActiveRunsForTheSameSession() {
        JdbcTemplate jdbcTemplate = createDatabase("same-session-runs.db");
        JdbcRunInputCoordinator inputs = inputCoordinator(jdbcTemplate);
        inputs.activateRun("session-1", "run-1");
        inputs.activateRun("session-1", "run-2");

        assertEquals(2, count(jdbcTemplate, "agent_active_runs"));

        inputs.closeRun("session-1", "run-1");

        assertEquals(1, count(jdbcTemplate, "agent_active_runs"));
        assertEquals(1, acceptingInputs(jdbcTemplate, "run-2"));
    }

    @Test
    void closedGateAlsoAcknowledgesSubmissionWithoutInsertingIt() {
        JdbcTemplate jdbcTemplate = createDatabase("closed-gate.db");
        JdbcRunInputCoordinator inputs = inputCoordinator(jdbcTemplate);
        inputs.activateRun("session-1", "run-1");
        jdbcTemplate.update(
                "update agent_active_runs set accepting_inputs = 0 where run_id = ?",
                "run-1");

        RunInputReceipt receipt = inputs.submitInput(
                "run-1", "best effort", "input-late");

        assertEquals(RunInputStatus.ACCEPTED, receipt.getStatus());
        assertEquals(0, count(jdbcTemplate, "agent_run_inputs"));
    }

    @Test
    void concurrentClaimsNeverReturnTheSameInputTwiceButNeedNotReturnWholeBatch() throws Exception {
        JdbcTemplate jdbcTemplate = createDatabase("concurrent-claim.db");
        JdbcRunInputCoordinator inputs = inputCoordinator(jdbcTemplate);
        inputs.activateRun("session-1", "run-1");
        inputs.submitInput("run-1", "one", "input-1");
        inputs.submitInput("run-1", "two", "input-2");
        inputs.submitInput("run-1", "three", "input-3");
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<List<RunInput>> first = executor.submit(() -> {
                start.await();
                return inputs.claimPendingInputs("session-1", "run-1", "turn-a");
            });
            Future<List<RunInput>> second = executor.submit(() -> {
                start.await();
                return inputs.claimPendingInputs("session-1", "run-1", "turn-b");
            });
            start.countDown();

            List<RunInput> allReturned = new ArrayList<RunInput>();
            allReturned.addAll(first.get(5, TimeUnit.SECONDS));
            allReturned.addAll(second.get(5, TimeUnit.SECONDS));
            Set<String> uniqueIds = new HashSet<String>();
            for (RunInput input : allReturned) {
                assertTrue(uniqueIds.add(input.getInputId()));
            }
            assertEquals(3, uniqueIds.size());
            assertEquals(
                    Integer.valueOf(3),
                    jdbcTemplate.queryForObject(
                            "select count(*) from agent_run_inputs where status = 'CLAIMED'",
                            Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closeRunCancelsUnclaimedInputsAndRemovesActiveRunWithoutAQueueTransaction() {
        JdbcTemplate jdbcTemplate = createDatabase("cancel.db");
        JdbcRunInputCoordinator inputs = inputCoordinator(jdbcTemplate);
        inputs.activateRun("session-1", "run-1");
        inputs.submitInput("run-1", "first", "input-1");
        inputs.submitInput("run-1", "second", "input-2");

        inputs.closeRun("session-1", "run-1");
        inputs.closeRun("session-1", "run-1");

        assertEquals(0, count(jdbcTemplate, "agent_active_runs"));
        List<String> statuses = jdbcTemplate.query(
                "select status from agent_run_inputs order by id",
                (rs, rowNum) -> rs.getString("status"));
        assertEquals(2, statuses.size());
        assertTrue(statuses.stream().allMatch("CANCELLED"::equals));

        RunInputReceipt late = inputs.submitInput("run-1", "new", "input-3");
        assertEquals(RunInputStatus.ACCEPTED, late.getStatus());
        assertEquals(2, count(jdbcTemplate, "agent_run_inputs"));
    }

    private void assertInputIds(List<RunInput> inputs, String... ids) {
        assertEquals(ids.length, inputs.size());
        for (int index = 0; index < ids.length; index++) {
            assertEquals(ids[index], inputs.get(index).getInputId());
        }
    }

    private void assertStatuses(List<RunInput> inputs, RunInputStatus... statuses) {
        assertEquals(statuses.length, inputs.size());
        for (int index = 0; index < statuses.length; index++) {
            assertEquals(statuses[index], inputs.get(index).getStatus());
        }
    }

    private int acceptingInputs(JdbcTemplate jdbcTemplate, String runId) {
        return jdbcTemplate.queryForObject(
                "select accepting_inputs from agent_active_runs "
                        + "where application_id = ? and run_id = ?",
                Integer.class,
                "default",
                runId);
    }

    private int count(JdbcTemplate jdbcTemplate, String table) {
        Integer value = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private JdbcTemplate createDatabase(String name) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve(name));
        dataSource.setBusyTimeout(5000);
        new JdbcSchemaInitializer(dataSource).initialize();
        return new JdbcTemplate(dataSource);
    }

    private JdbcRunInputCoordinator inputCoordinator(JdbcTemplate jdbcTemplate) {
        return new JdbcRunInputCoordinator(jdbcTemplate, new ObjectMapper(), storeProperties());
    }

    private MessageImage image(String name, String mediaType, String url, String detail) {
        MessageImage image = new MessageImage();
        image.setName(name);
        image.setMediaType(mediaType);
        image.setUrl(url);
        image.setDetail(detail);
        return image;
    }

    private void assertImage(MessageImage actual, MessageImage expected) {
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getMediaType(), actual.getMediaType());
        assertEquals(expected.getUrl(), actual.getUrl());
        assertEquals(expected.getDetail(), actual.getDetail());
    }

    private JdbcStoreProperties storeProperties() {
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId("default");
        return properties;
    }
}

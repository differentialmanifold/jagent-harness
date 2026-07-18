package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalDecision;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcToolApprovalCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesPendingApprovalAcrossCoordinatorInstances() throws Exception {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcToolApprovalCoordinator owner = coordinator(jdbcTemplate);
        JdbcToolApprovalCoordinator apiInstance = coordinator(jdbcTemplate);
        ToolApprovalRequest request = approvalRequest();
        CountDownLatch pending = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolApprovalDecision> future = executor.submit(() -> owner.awaitDecision(
                    "run-1",
                    "session-1",
                    request,
                    StopSignal.none(),
                    () -> pending.countDown()));

            assertTrue(pending.await(1, TimeUnit.SECONDS));
            assertNotNull(jdbcTemplate.queryForObject(
                    "select id from agent_approvals "
                            + "where application_id = ? and run_id = ? and approval_id = ?",
                    Long.class,
                    "default",
                    "run-1",
                    "approval-1"));
            assertTrue(apiInstance.resolve("run-1", "approval-1", true, "approved"));

            ToolApprovalDecision decision = future.get(2, TimeUnit.SECONDS);
            assertTrue(decision.isApproved());
            assertEquals("approved", decision.getReason());
            assertFalse(apiInstance.resolve("run-1", "approval-1", true, "late"));
            assertEquals(
                    "APPROVED",
                    jdbcTemplate.queryForObject(
                            "select status from agent_approvals "
                                    + "where application_id = ? and run_id = ? and approval_id = ?",
                            String.class,
                            "default",
                            "run-1",
                            "approval-1"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelRunDeniesPendingApprovalAcrossCoordinatorInstances() throws Exception {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcToolApprovalCoordinator owner = coordinator(jdbcTemplate);
        JdbcToolApprovalCoordinator apiInstance = coordinator(jdbcTemplate);
        ToolApprovalRequest request = approvalRequest();
        CountDownLatch pending = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolApprovalDecision> future = executor.submit(() -> owner.awaitDecision(
                    "run-1",
                    "session-1",
                    request,
                    StopSignal.none(),
                    () -> pending.countDown()));

            assertTrue(pending.await(1, TimeUnit.SECONDS));
            apiInstance.cancelRun("run-1");

            ToolApprovalDecision decision = future.get(2, TimeUnit.SECONDS);
            assertFalse(decision.isApproved());
            assertEquals("Tool approval was cancelled", decision.getReason());
            assertFalse(apiInstance.resolve("run-1", "approval-1", true, "late"));
            assertEquals(
                    "CANCELLED",
                    jdbcTemplate.queryForObject(
                            "select status from agent_approvals "
                                    + "where application_id = ? and run_id = ? and approval_id = ?",
                            String.class,
                            "default",
                            "run-1",
                            "approval-1"));
        } finally {
            executor.shutdownNow();
        }
    }

    private JdbcTemplate createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("approval-coordinator.db"));
        new JdbcSchemaInitializer(dataSource).initialize();
        return new JdbcTemplate(dataSource);
    }

    private JdbcToolApprovalCoordinator coordinator(JdbcTemplate jdbcTemplate) {
        JdbcToolApprovalProperties properties = new JdbcToolApprovalProperties();
        properties.setPollIntervalMillis(20L);
        return new JdbcToolApprovalCoordinator(jdbcTemplate, new ObjectMapper(), storeProperties("default"), properties);
    }

    private JdbcStoreProperties storeProperties(String applicationId) {
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId(applicationId);
        return properties;
    }

    private ToolApprovalRequest approvalRequest() {
        return new ToolApprovalRequest(
                "approval-1",
                "tool-call-1",
                "write",
                "Approve write",
                "Approve this write",
                "write",
                "/tmp/example.txt",
                Collections.<String, Object>emptyMap());
    }
}

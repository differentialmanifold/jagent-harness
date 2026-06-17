package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalDecision;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import org.junit.jupiter.api.Test;

class ToolApprovalCoordinatorTest {

    @Test
    void resolvesPendingApprovalAfterRegistration() throws Exception {
        ToolApprovalCoordinator coordinator = new ToolApprovalCoordinator();
        ToolApprovalRequest request = approvalRequest();
        CountDownLatch pending = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolApprovalDecision> future = executor.submit(() -> coordinator.awaitDecision(
                    "request-1",
                    request,
                    StopSignal.none(),
                    () -> pending.countDown()));

            assertTrue(pending.await(1, TimeUnit.SECONDS));
            assertTrue(coordinator.resolve("request-1", "approval-1", true, "approved"));
            ToolApprovalDecision decision = future.get(1, TimeUnit.SECONDS);

            assertTrue(decision.isApproved());
            assertFalse(coordinator.resolve("request-1", "approval-1", true, "late"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelRequestDeniesPendingApproval() throws Exception {
        ToolApprovalCoordinator coordinator = new ToolApprovalCoordinator();
        ToolApprovalRequest request = approvalRequest();
        CountDownLatch pending = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolApprovalDecision> future = executor.submit(() -> coordinator.awaitDecision(
                    "request-1",
                    request,
                    StopSignal.none(),
                    () -> pending.countDown()));

            assertTrue(pending.await(1, TimeUnit.SECONDS));
            coordinator.cancelRequest("request-1");
            ToolApprovalDecision decision = future.get(1, TimeUnit.SECONDS);

            assertFalse(decision.isApproved());
        } finally {
            executor.shutdownNow();
        }
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

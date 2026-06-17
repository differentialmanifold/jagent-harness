package io.github.differentialmanifold.jagentharness.spring.web;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalDecision;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;

public class ToolApprovalCoordinator {

    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<String, PendingApproval>();

    public ToolApprovalDecision awaitDecision(String requestId,
                                              ToolApprovalRequest request,
                                              StopSignal stopSignal,
                                              Runnable onPending) throws Exception {
        PendingApproval pending = new PendingApproval();
        approvals.put(key(requestId, request.getApprovalId()), pending);
        try {
            if (onPending != null) {
                onPending.run();
            }
            while (true) {
                if (stopSignal != null) {
                    stopSignal.throwIfAborted();
                }
                try {
                    return pending.future.get(250, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Keep checking the stop signal while the UI is waiting for user input.
                }
            }
        } finally {
            approvals.remove(key(requestId, request.getApprovalId()), pending);
        }
    }

    public boolean resolve(String requestId, String approvalId, boolean approved, String reason) {
        PendingApproval pending = approvals.get(key(requestId, approvalId));
        if (pending == null) {
            return false;
        }
        return pending.future.complete(approved
                ? ToolApprovalDecision.approved(reason)
                : ToolApprovalDecision.denied(reason));
    }

    public void cancelRequest(String requestId) {
        String prefix = requestId + ":";
        for (Map.Entry<String, PendingApproval> entry : approvals.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                entry.getValue().future.complete(ToolApprovalDecision.denied("Tool approval was cancelled"));
                approvals.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private String key(String requestId, String approvalId) {
        return requestId + ":" + approvalId;
    }

    private static class PendingApproval {
        private final CompletableFuture<ToolApprovalDecision> future = new CompletableFuture<ToolApprovalDecision>();
    }
}

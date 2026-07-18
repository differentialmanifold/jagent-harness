package io.github.differentialmanifold.jagentharness.core.tool;

import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;

public interface ToolApprovalCoordinator {

    ToolApprovalDecision awaitDecision(String runId,
                                       String sessionId,
                                       ToolApprovalRequest request,
                                       StopSignal stopSignal,
                                       Runnable onPending) throws Exception;

    boolean resolve(String runId, String approvalId, boolean approved, String reason);

    void cancelRun(String runId);
}

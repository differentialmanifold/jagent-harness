package io.github.differentialmanifold.jagentharness.example.coding.execution;

import io.github.differentialmanifold.jagentharness.example.coding.approval.*;
import java.nio.file.Path;

public final class CodingContext {
    private final Path workspaceRoot;
    private final StopSignal stopSignal;
    private final ToolApprovalMode approvalMode;
    private final ToolApprovalHandler approvalHandler;
    private final String callId, toolName;

    public CodingContext(Path workspaceRoot) {
        this(workspaceRoot, StopSignal.none(), ToolApprovalMode.FULL_ACCESS, null, null, null);
    }

    public CodingContext(
            Path workspaceRoot,
            StopSignal stopSignal,
            ToolApprovalMode approvalMode,
            ToolApprovalHandler approvalHandler,
            String callId,
            String toolName) {
        this.workspaceRoot = workspaceRoot;
        this.stopSignal = stopSignal;
        this.approvalMode = approvalMode;
        this.approvalHandler = approvalHandler;
        this.callId = callId;
        this.toolName = toolName;
    }

    public ToolApprovalMode getApprovalMode() {
        return approvalMode;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public StopSignal getStopSignal() {
        return stopSignal;
    }

    public CodingContext forToolCall(String id, String name) {
        return new CodingContext(
                workspaceRoot, stopSignal, approvalMode, approvalHandler, id, name);
    }

    public ToolApprovalDecision requestApproval(ToolApprovalRequest request) throws Exception {
        stopSignal.throwIfAborted();
        if (approvalMode == ToolApprovalMode.FULL_ACCESS)
            return ToolApprovalDecision.approved("full_access");
        if (approvalHandler == null)
            throw new IllegalStateException("Approval handler is required");
        ToolApprovalDecision decision =
                approvalHandler.requestApproval(
                        request.withCodingContext(callId, toolName), stopSignal);
        stopSignal.throwIfAborted();
        if (decision == null || !decision.isApproved())
            throw new ToolApprovalRejectedException(
                    decision == null ? "Denied" : decision.getReason());
        return decision;
    }
}

package io.github.differentialmanifold.jagentharness.core.tool;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;

public class ToolContext extends AgentContext {

    private final StopSignal stopSignal;
    private final ToolApprovalMode approvalMode;
    private final ToolApprovalHandler approvalHandler;
    private final String currentToolCallId;
    private final String currentToolName;

    public ToolContext(String sessionId, String turnId) {
        this(sessionId, turnId, null, null, null, Collections.<String, Object>emptyMap());
    }

    public ToolContext(String sessionId, String turnId, Path workspaceRoot) {
        this(sessionId, turnId, null, workspaceRoot, null, Collections.<String, Object>emptyMap());
    }

    public ToolContext(String sessionId,
                       String turnId,
                       String traceId,
                       Map<String, Object> attributes) {
        this(sessionId, turnId, traceId, null, null, attributes);
    }

    public ToolContext(String sessionId,
                       String turnId,
                       String traceId,
                       Path workspaceRoot,
                       Path configRoot,
                       Map<String, Object> attributes) {
        this(sessionId, turnId, traceId, workspaceRoot, configRoot, attributes, StopSignal.none());
    }

    public ToolContext(String sessionId,
                       String turnId,
                       String traceId,
                       Path workspaceRoot,
                       Path configRoot,
                       Map<String, Object> attributes,
                       StopSignal stopSignal) {
        this(sessionId,
                turnId,
                traceId,
                workspaceRoot,
                configRoot,
                attributes,
                stopSignal,
                ToolApprovalMode.FULL_ACCESS,
                null,
                null,
                null);
    }

    public ToolContext(String sessionId,
                       String turnId,
                       String traceId,
                       Path workspaceRoot,
                       Path configRoot,
                       Map<String, Object> attributes,
                       StopSignal stopSignal,
                       ToolApprovalMode approvalMode,
                       ToolApprovalHandler approvalHandler,
                       String currentToolCallId,
                       String currentToolName) {
        this(sessionId, turnId, traceId, workspaceRoot, configRoot, attributes, stopSignal, approvalMode,
                approvalHandler, currentToolCallId, currentToolName, null);
    }

    public ToolContext(String sessionId,
                       String turnId,
                       String traceId,
                       Path workspaceRoot,
                       Path configRoot,
                       Map<String, Object> attributes,
                       StopSignal stopSignal,
                       ToolApprovalMode approvalMode,
                       ToolApprovalHandler approvalHandler,
                       String currentToolCallId,
                       String currentToolName,
                       String projectId) {
        super(sessionId, turnId, traceId, workspaceRoot, configRoot, attributes, projectId);
        this.stopSignal = stopSignal == null ? StopSignal.none() : stopSignal;
        this.approvalMode = approvalMode == null ? ToolApprovalMode.FULL_ACCESS : approvalMode;
        this.approvalHandler = approvalHandler;
        this.currentToolCallId = currentToolCallId;
        this.currentToolName = currentToolName;
    }

    public StopSignal getStopSignal() {
        return stopSignal;
    }

    public ToolApprovalMode getApprovalMode() {
        return approvalMode;
    }

    public ToolApprovalHandler getApprovalHandler() {
        return approvalHandler;
    }

    public String getCurrentToolCallId() {
        return currentToolCallId;
    }

    public String getCurrentToolName() {
        return currentToolName;
    }

    public ToolContext forToolCall(String toolCallId, String toolName) {
        return new ToolContext(
                getSessionId(),
                getTurnId(),
                getTraceId(),
                getWorkspaceRoot(),
                getConfigRoot(),
                getAttributes(),
                stopSignal,
                approvalMode,
                approvalHandler,
                toolCallId,
                toolName,
                getProjectId());
    }

    public ToolApprovalDecision requestApproval(ToolApprovalRequest request) throws Exception {
        stopSignal.throwIfAborted();
        if (approvalMode == ToolApprovalMode.FULL_ACCESS) {
            return ToolApprovalDecision.approved("full_access");
        }
        if (approvalHandler == null) {
            throw new IllegalStateException("Tool approval handler is required when approval mode is ask_for_approval");
        }
        ToolApprovalRequest effectiveRequest = request == null
                ? new ToolApprovalRequest("Approve tool execution", "", "", "")
                : request;
        ToolApprovalDecision decision = approvalHandler.requestApproval(
                effectiveRequest.withToolContext(currentToolCallId, currentToolName),
                stopSignal);
        stopSignal.throwIfAborted();
        if (decision == null || !decision.isApproved()) {
            String reason = decision == null ? "" : decision.getReason();
            throw new ToolApprovalRejectedException(reason == null || reason.trim().isEmpty()
                    ? "Tool execution was denied by the user"
                    : reason);
        }
        return decision;
    }
}

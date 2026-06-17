package io.github.differentialmanifold.jagentharness.core.tool;

import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;

public interface ToolApprovalHandler {

    ToolApprovalDecision requestApproval(ToolApprovalRequest request, StopSignal stopSignal) throws Exception;
}

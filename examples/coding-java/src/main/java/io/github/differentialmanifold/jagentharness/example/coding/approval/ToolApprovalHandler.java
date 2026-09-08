package io.github.differentialmanifold.jagentharness.example.coding.approval;

import io.github.differentialmanifold.jagentharness.example.coding.execution.StopSignal;

public interface ToolApprovalHandler {

    ToolApprovalDecision requestApproval(ToolApprovalRequest request, StopSignal stopSignal)
            throws Exception;
}

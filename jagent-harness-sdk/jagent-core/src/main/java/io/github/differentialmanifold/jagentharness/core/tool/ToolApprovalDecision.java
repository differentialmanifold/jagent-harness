package io.github.differentialmanifold.jagentharness.core.tool;

public class ToolApprovalDecision {

    private final boolean approved;
    private final String reason;

    private ToolApprovalDecision(boolean approved, String reason) {
        this.approved = approved;
        this.reason = reason;
    }

    public static ToolApprovalDecision approved() {
        return new ToolApprovalDecision(true, "");
    }

    public static ToolApprovalDecision approved(String reason) {
        return new ToolApprovalDecision(true, reason == null ? "" : reason);
    }

    public static ToolApprovalDecision denied(String reason) {
        return new ToolApprovalDecision(false, reason == null ? "" : reason);
    }

    public boolean isApproved() {
        return approved;
    }

    public String getReason() {
        return reason;
    }
}

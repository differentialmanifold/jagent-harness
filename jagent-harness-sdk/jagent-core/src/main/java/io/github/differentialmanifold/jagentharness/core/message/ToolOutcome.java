package io.github.differentialmanifold.jagentharness.core.message;

/** Structured execution status, stored separately from the model-visible tool output. */
public final class ToolOutcome {
    private ToolOutcome() {}

    public static void record(AgentMessage message, String status) {
        if (!"SUCCEEDED".equals(status) && !"FAILED".equals(status) && !"UNKNOWN".equals(status))
            throw new IllegalArgumentException("Invalid tool status: " + status);
        message.setMetadataJson("{\"kind\":\"tool_result\",\"status\":\"" + status + "\"}");
    }

    public static boolean failed(AgentMessage message) {
        return "{\"kind\":\"tool_result\",\"status\":\"FAILED\"}".equals(message.getMetadataJson());
    }
}

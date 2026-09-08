package io.github.differentialmanifold.jagentharness.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;

/** Shared result value. Status expresses an execution outcome, not a delivery guarantee. */
public final class ToolExecutionResult {
    public final String status;
    public final Object content;

    private ToolExecutionResult(String status, Object content) {
        this.status = status;
        this.content = content;
    }

    public static ToolExecutionResult of(String content) {
        return success(content == null ? "" : content);
    }

    public static ToolExecutionResult success(Object content) {
        return new ToolExecutionResult("SUCCEEDED", content);
    }

    public static ToolExecutionResult failure(Object content) {
        return new ToolExecutionResult("FAILED", content);
    }

    public static ToolExecutionResult unknown(Object content) {
        return new ToolExecutionResult("UNKNOWN", content);
    }

    public static ToolExecutionResult error(String message) {
        return failure(Collections.singletonMap("error", message));
    }

    public String getContent() {
        if (content instanceof String) return (String) content;
        try {
            return new ObjectMapper().writeValueAsString(content);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid tool result", e);
        }
    }
}

package io.github.differentialmanifold.jagentharness.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;

/** Shared result value. Status expresses an execution outcome, not a delivery guarantee. */
public final class ToolExecutionResult {
    public final String status;
    public final Object content;
    public final java.util.List<java.util.Map<String, Object>> images;

    public ToolExecutionResult withImages(java.util.List<java.util.Map<String, Object>> value) {
        return new ToolExecutionResult(status, content, value);
    }

    private ToolExecutionResult(String status, Object content) {
        this(status, content, java.util.Collections.emptyList());
    }

    private ToolExecutionResult(
            String status, Object content, java.util.List<java.util.Map<String, Object>> images) {
        this.status = status;
        this.content = content;
        this.images =
                images == null
                        ? java.util.Collections.emptyList()
                        : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(images));
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

package io.github.differentialmanifold.jagentharness.core.tool;

public class ToolExecutionResult {

    private final String content;

    private ToolExecutionResult(String content) {
        this.content = content;
    }

    public static ToolExecutionResult of(String content) {
        return new ToolExecutionResult(content == null ? "" : content);
    }

    public static ToolExecutionResult error(String message) {
        return new ToolExecutionResult("{\"error\":\"" + escape(message) + "\"}");
    }

    public String getContent() {
        return content;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

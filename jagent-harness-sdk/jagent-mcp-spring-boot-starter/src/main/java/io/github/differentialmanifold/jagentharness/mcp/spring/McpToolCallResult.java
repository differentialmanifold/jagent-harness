package io.github.differentialmanifold.jagentharness.mcp.spring;

import com.fasterxml.jackson.databind.JsonNode;

public class McpToolCallResult {

    private final boolean success;
    private final String error;
    private final JsonNode result;

    McpToolCallResult(boolean success, String error, JsonNode result) {
        this.success = success;
        this.error = error;
        this.result = result;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }

    public JsonNode getResult() {
        return result;
    }
}

package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class ToolCallResponse {

    private final String result;

    public ToolCallResponse(String result) {
        this.result = result == null ? "" : result;
    }

    public String getResult() {
        return result;
    }
}

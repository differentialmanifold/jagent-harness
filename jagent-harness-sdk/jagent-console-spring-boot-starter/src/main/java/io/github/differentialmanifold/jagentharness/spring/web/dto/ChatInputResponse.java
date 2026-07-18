package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class ChatInputResponse {

    private final String inputId;
    private final String status;

    public ChatInputResponse(String inputId, String status) {
        this.inputId = inputId;
        this.status = status;
    }

    public String getInputId() {
        return inputId;
    }

    public String getStatus() {
        return status;
    }
}

package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class ChatInputRequest {

    private String inputId;
    private String content;

    public String getInputId() {
        return inputId;
    }

    public void setInputId(String inputId) {
        this.inputId = inputId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}

package io.github.differentialmanifold.jagentharness.spring.web.dto;

import java.util.List;

public class ChatInputRequest {

    private String inputId;
    private String content;
    private List<ChatImageRequest> images;

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

    public List<ChatImageRequest> getImages() {
        return images;
    }

    public void setImages(List<ChatImageRequest> images) {
        this.images = images;
    }
}

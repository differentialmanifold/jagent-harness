package io.github.differentialmanifold.jagentharness.spring.web.dto;

import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import java.util.List;

public class ChatInputRequest {

    private String inputId;
    private String content;
    private List<MessageImage> images;

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

    public List<MessageImage> getImages() {
        return images;
    }

    public void setImages(List<MessageImage> images) {
        this.images = images;
    }
}

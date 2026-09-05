package io.github.differentialmanifold.jagentharness.core.agent;

import java.util.ArrayList;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.message.MessageImage;

public class RunInput {

    private String inputId;
    private String sessionId;
    private String runId;
    private String content;
    private List<MessageImage> images = new ArrayList<MessageImage>();
    private RunInputStatus status;

    public RunInput() {
    }

    public RunInput(String inputId,
                    String sessionId,
                    String runId,
                    String content,
                    RunInputStatus status) {
        this(inputId, sessionId, runId, content, null, status);
    }

    public RunInput(String inputId,
                    String sessionId,
                    String runId,
                    String content,
                    List<MessageImage> images,
                    RunInputStatus status) {
        this.inputId = inputId;
        this.sessionId = sessionId;
        this.runId = runId;
        this.content = content;
        setImages(images);
        this.status = status;
    }

    public String getInputId() {
        return inputId;
    }

    public void setInputId(String inputId) {
        this.inputId = inputId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
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
        this.images = images == null
                ? new ArrayList<MessageImage>()
                : new ArrayList<MessageImage>(images);
    }

    public RunInputStatus getStatus() {
        return status;
    }

    public void setStatus(RunInputStatus status) {
        this.status = status;
    }
}

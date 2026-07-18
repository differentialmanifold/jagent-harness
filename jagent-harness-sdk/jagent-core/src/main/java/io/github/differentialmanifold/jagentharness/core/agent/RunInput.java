package io.github.differentialmanifold.jagentharness.core.agent;

public class RunInput {

    private String inputId;
    private String sessionId;
    private String runId;
    private String content;
    private RunInputStatus status;

    public RunInput() {
    }

    public RunInput(String inputId,
                    String sessionId,
                    String runId,
                    String content,
                    RunInputStatus status) {
        this.inputId = inputId;
        this.sessionId = sessionId;
        this.runId = runId;
        this.content = content;
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

    public RunInputStatus getStatus() {
        return status;
    }

    public void setStatus(RunInputStatus status) {
        this.status = status;
    }
}

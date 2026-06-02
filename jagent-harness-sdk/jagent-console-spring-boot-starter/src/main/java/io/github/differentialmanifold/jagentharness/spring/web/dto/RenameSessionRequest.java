package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class RenameSessionRequest {

    private String sessionId;
    private String title;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}

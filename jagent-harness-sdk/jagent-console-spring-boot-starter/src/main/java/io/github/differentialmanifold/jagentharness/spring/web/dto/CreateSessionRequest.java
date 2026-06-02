package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class CreateSessionRequest {

    private String title;
    private String workspacePath;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }
}

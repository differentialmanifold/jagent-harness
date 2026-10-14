package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class CreateSessionRequest {

    private String runnerId;

    public String getRunnerId() {
        return runnerId;
    }

    public void setRunnerId(String runnerId) {
        this.runnerId = runnerId;
    }

    private java.util.List<String> enabledTools;

    public java.util.List<String> getEnabledTools() {
        return enabledTools;
    }

    public void setEnabledTools(java.util.List<String> enabledTools) {
        this.enabledTools = enabledTools;
    }

    private String title;
    private String workspacePath;
    private String projectName;
    private String projectId;

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

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }
}

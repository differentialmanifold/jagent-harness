package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class PromptPreviewResponse {

    private String systemPrompt;
    private String workspaceRoot;

    public PromptPreviewResponse() {
    }

    public PromptPreviewResponse(String systemPrompt, String workspaceRoot) {
        this.systemPrompt = systemPrompt;
        this.workspaceRoot = workspaceRoot;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }
}

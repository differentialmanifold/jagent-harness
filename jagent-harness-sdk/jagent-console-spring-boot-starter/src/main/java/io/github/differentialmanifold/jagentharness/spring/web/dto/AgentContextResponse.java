package io.github.differentialmanifold.jagentharness.spring.web.dto;

import java.util.List;

public class AgentContextResponse {

    private List<ToolInfoResponse> tools;
    private List<PromptFileResponse> promptFiles;
    private List<SkillInfoResponse> skills;
    private String configRoot;
    private String workspaceRoot;

    public AgentContextResponse() {
    }

    public AgentContextResponse(List<ToolInfoResponse> tools,
                                List<PromptFileResponse> promptFiles,
                                List<SkillInfoResponse> skills,
                                String configRoot,
                                String workspaceRoot) {
        this.tools = tools;
        this.promptFiles = promptFiles;
        this.skills = skills;
        this.configRoot = configRoot;
        this.workspaceRoot = workspaceRoot;
    }

    public List<ToolInfoResponse> getTools() {
        return tools;
    }

    public List<PromptFileResponse> getPromptFiles() {
        return promptFiles;
    }

    public List<SkillInfoResponse> getSkills() {
        return skills;
    }

    public String getConfigRoot() {
        return configRoot;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }
}

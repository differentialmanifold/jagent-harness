package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class SkillInfoResponse {

    private String name;
    private String description;
    private String filePath;
    private String scope;

    public SkillInfoResponse() {
    }

    public SkillInfoResponse(String name, String description, String filePath, String scope) {
        this.name = name;
        this.description = description;
        this.filePath = filePath;
        this.scope = scope;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getScope() {
        return scope;
    }
}

package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class PromptFileResponse {

    private String name;
    private String path;
    private boolean exists;
    private String mode;
    private String description;

    public PromptFileResponse() {
    }

    public PromptFileResponse(String name, String path, boolean exists, String mode, String description) {
        this.name = name;
        this.path = path;
        this.exists = exists;
        this.mode = mode;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public boolean isExists() {
        return exists;
    }

    public String getMode() {
        return mode;
    }

    public String getDescription() {
        return description;
    }
}

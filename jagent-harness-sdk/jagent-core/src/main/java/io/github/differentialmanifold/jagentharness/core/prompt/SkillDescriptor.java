package io.github.differentialmanifold.jagentharness.core.prompt;

public class SkillDescriptor {

    private String name;
    private String description;
    private String filePath;

    public SkillDescriptor(String name, String description, String filePath) {
        this.name = name;
        this.description = description;
        this.filePath = filePath;
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
}

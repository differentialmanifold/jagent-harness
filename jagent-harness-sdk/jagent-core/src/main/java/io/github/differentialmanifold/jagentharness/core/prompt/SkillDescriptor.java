package io.github.differentialmanifold.jagentharness.core.prompt;

public class SkillDescriptor {

    private String name;
    private String description;
    private String filePath;
    private String source;

    public SkillDescriptor(String name, String description, String filePath) {
        this(name, description, filePath, null);
    }

    public SkillDescriptor(String name, String description, String filePath, String source) {
        this.name = name;
        this.description = description;
        this.filePath = filePath;
        this.source = source;
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

    public String getSource() {
        return source;
    }
}

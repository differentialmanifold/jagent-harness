package io.github.differentialmanifold.jagentharness.core.prompt;

public class SkillDescriptor {

    private String name;
    private String description;
    private String filePath;
    private String directoryPath;

    public SkillDescriptor(String name, String description, String filePath) {
        this(name, description, filePath, null);
    }

    public SkillDescriptor(String name, String description, String filePath, String directoryPath) {
        this.name = name;
        this.description = description;
        this.filePath = filePath;
        this.directoryPath = directoryPath;
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

    public String getDirectoryPath() {
        return directoryPath;
    }
}

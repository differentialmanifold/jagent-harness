package io.github.differentialmanifold.jagentharness.core.prompt;

import java.time.Instant;

public class SkillManifest {

    private final String skillKey;
    private final String skillDirPath;
    private final String skillFilePath;
    private final String name;
    private final String description;
    private final Instant updatedAt;

    public SkillManifest(String skillKey,
                         String skillDirPath,
                         String skillFilePath,
                         String name,
                         String description,
                         Instant updatedAt) {
        this.skillKey = skillKey;
        this.skillDirPath = skillDirPath;
        this.skillFilePath = skillFilePath;
        this.name = name;
        this.description = description;
        this.updatedAt = updatedAt;
    }

    public String getSkillKey() {
        return skillKey;
    }

    public String getSkillDirPath() {
        return skillDirPath;
    }

    public String getSkillFilePath() {
        return skillFilePath;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

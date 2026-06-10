package io.github.differentialmanifold.jagentharness.core.prompt;

import java.time.Instant;

public class PromptBinding {

    private final String promptName;
    private final String filePath;
    private final int priority;
    private final Instant updatedAt;

    public PromptBinding(String promptName, String filePath, int priority, Instant updatedAt) {
        this.promptName = promptName;
        this.filePath = filePath;
        this.priority = priority;
        this.updatedAt = updatedAt;
    }

    public String getPromptName() {
        return promptName;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getPriority() {
        return priority;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

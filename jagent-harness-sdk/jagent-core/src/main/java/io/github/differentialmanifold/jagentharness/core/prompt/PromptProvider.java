package io.github.differentialmanifold.jagentharness.core.prompt;

public interface PromptProvider {

    String buildSystemPrompt(PromptContext context);
}

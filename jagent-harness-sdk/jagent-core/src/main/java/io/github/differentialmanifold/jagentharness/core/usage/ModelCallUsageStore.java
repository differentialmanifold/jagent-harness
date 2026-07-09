package io.github.differentialmanifold.jagentharness.core.usage;

public interface ModelCallUsageStore {

    ModelCallUsage findLatestBySessionId(String sessionId);

    void append(ModelCallUsage usage);
}

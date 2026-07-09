package io.github.differentialmanifold.jagentharness.core.usage;

public class NoopModelCallUsageStore implements ModelCallUsageStore {

    @Override
    public ModelCallUsage findLatestBySessionId(String sessionId) {
        return null;
    }

    @Override
    public void append(ModelCallUsage usage) {
    }
}

package io.github.differentialmanifold.jagentharness.core.agent;

public interface StopRegistration extends AutoCloseable {

    @Override
    void close();
}

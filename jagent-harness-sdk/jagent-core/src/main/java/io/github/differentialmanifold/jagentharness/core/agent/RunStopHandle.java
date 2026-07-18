package io.github.differentialmanifold.jagentharness.core.agent;

public interface RunStopHandle extends StopSignal, AutoCloseable {

    String getRunId();

    String getSessionId();

    @Override
    void close();
}

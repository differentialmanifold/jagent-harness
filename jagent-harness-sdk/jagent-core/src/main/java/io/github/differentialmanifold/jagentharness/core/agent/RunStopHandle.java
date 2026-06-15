package io.github.differentialmanifold.jagentharness.core.agent;

public interface RunStopHandle extends StopSignal, AutoCloseable {

    String getRequestId();

    String getSessionId();

    @Override
    void close();
}

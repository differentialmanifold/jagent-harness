package io.github.differentialmanifold.jagentharness.core.agent;

public interface RunStopCoordinator {

    RunStopHandle register(String requestId, String sessionId);

    StopRequestResult requestStop(String requestId);
}

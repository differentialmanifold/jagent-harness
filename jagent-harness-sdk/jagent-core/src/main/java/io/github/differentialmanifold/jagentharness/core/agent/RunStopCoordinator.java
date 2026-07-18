package io.github.differentialmanifold.jagentharness.core.agent;

public interface RunStopCoordinator {

    RunStopHandle register(String runId, String sessionId);

    StopRequestResult requestStop(String runId);
}

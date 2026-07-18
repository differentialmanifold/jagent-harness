package io.github.differentialmanifold.jagentharness.core.agent;

public interface RunInputCoordinator extends RunInputSource {

    void activateRun(String sessionId, String runId);

    RunInputReceipt submitInput(String runId, String content, String inputId);

    void closeRun(String sessionId, String runId);
}

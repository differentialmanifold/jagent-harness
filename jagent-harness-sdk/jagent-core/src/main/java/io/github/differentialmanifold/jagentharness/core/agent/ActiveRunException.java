package io.github.differentialmanifold.jagentharness.core.agent;

public class ActiveRunException extends RuntimeException {

    public ActiveRunException(String runId) {
        super("Run is already active: " + runId);
    }
}

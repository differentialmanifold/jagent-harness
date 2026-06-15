package io.github.differentialmanifold.jagentharness.core.agent;

public class ActiveRunException extends RuntimeException {

    public ActiveRunException(String requestId) {
        super("Request is already active: " + requestId);
    }
}

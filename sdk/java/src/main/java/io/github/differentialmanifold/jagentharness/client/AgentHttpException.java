package io.github.differentialmanifold.jagentharness.client;

public class AgentHttpException extends java.io.IOException {
    public final int status;

    public AgentHttpException(int status, String message) {
        super(message);
        this.status = status;
    }
}

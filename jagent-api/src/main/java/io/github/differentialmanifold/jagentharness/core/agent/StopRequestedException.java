package io.github.differentialmanifold.jagentharness.core.agent;

public class StopRequestedException extends RuntimeException {

    public StopRequestedException() {
        super("Agent run stopped");
    }

    public StopRequestedException(Throwable cause) {
        super("Agent run stopped", cause);
    }
}

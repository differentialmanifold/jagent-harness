package io.github.differentialmanifold.jagentharness.core.execution;

public final class ProtocolException extends RuntimeException {
    public final int status;
    public final String code;

    public ProtocolException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}

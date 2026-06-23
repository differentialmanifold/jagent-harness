package io.github.differentialmanifold.jagentharness.core.provider;

public class ModelProviderException extends RuntimeException {

    private final boolean retryable;

    public ModelProviderException(String message) {
        this(message, null, false);
    }

    public ModelProviderException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public ModelProviderException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

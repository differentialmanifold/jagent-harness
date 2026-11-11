package io.github.differentialmanifold.jagentharness.core.agent;

/** A repeated failure persisted after the model was asked to change approach. */
public final class NoProgressException extends RuntimeException {
    public static final String CODE = "NO_PROGRESS";

    public NoProgressException() {
        super(
                "Stopped because the same tools, arguments and errors repeated after a recovery reminder. "
                        + "Inspect the tool errors, fix the blocker or change the approach, then send a new message to continue.");
    }
}

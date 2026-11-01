package io.github.differentialmanifold.jagentharness.core.session;

/** A session was deleted or does not exist in the current store. */
public class SessionNotFoundException extends IllegalArgumentException {
    public SessionNotFoundException(String sessionId) {
        super("Session not found: " + sessionId);
    }
}

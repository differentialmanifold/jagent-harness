package io.github.differentialmanifold.jagentharness.core.personal;

/** Agent operation failure; transports map the kind to their own error representation. */
public final class PersonalAgentException extends RuntimeException {
    public enum Kind {
        NOT_FOUND,
        CONFLICT,
        FORBIDDEN,
        INVALID,
        UNAVAILABLE,
        FAILED
    }

    public final Kind kind;
    public final String code;

    public PersonalAgentException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }
}

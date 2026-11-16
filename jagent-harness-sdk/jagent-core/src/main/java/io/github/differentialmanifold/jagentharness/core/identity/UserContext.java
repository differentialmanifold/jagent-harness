package io.github.differentialmanifold.jagentharness.core.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Trusted execution identity. Capture it when scheduling work; always restore the caller. */
public final class UserContext implements AutoCloseable {
    public static final String LOCAL = "local";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private final String previous;

    private UserContext(String user) {
        previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(user));
    }

    public static UserContext enter(String user) {
        return new UserContext(user);
    }

    public static String current() {
        return CURRENT.get() == null ? LOCAL : CURRENT.get();
    }

    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    public static Runnable capture(Runnable action) {
        String user = current();
        return () -> {
            try (UserContext ignored = enter(user)) {
                action.run();
            }
        };
    }

    public static <T> Consumer<T> consumer(Consumer<T> action) {
        String user = current();
        return value -> {
            try (UserContext ignored = enter(user)) {
                action.accept(value);
            }
        };
    }

    public static <T, U> BiConsumer<T, U> callback(BiConsumer<T, U> action) {
        String user = current();
        return (a, b) -> {
            try (UserContext ignored = enter(user)) {
                action.accept(a, b);
            }
        };
    }

    public static String key(String value) {
        return current() + ":" + value;
    }
    /**
     * A bounded, collision-resistant storage namespace; application configuration stays unchanged.
     */
    public static String namespace(String application) {
        if (LOCAL.equals(current())) return application;
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (application + "\0" + current())
                                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("user:");
            for (byte b : hash) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

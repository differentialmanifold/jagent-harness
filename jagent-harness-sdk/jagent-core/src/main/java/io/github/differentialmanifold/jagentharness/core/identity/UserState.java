package io.github.differentialmanifold.jagentharness.core.identity;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** In-memory runtime state partitioned exactly like persistent user data. */
public final class UserState<T> {
    private final ConcurrentHashMap<String, T> values = new ConcurrentHashMap<>();
    private final Supplier<T> factory;

    public UserState(Supplier<T> factory) {
        this.factory = factory;
    }

    public T get() {
        return values.computeIfAbsent(UserContext.current(), key -> factory.get());
    }

    public Collection<T> all() {
        return values.values();
    }
}

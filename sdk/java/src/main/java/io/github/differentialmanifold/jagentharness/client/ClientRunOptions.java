package io.github.differentialmanifold.jagentharness.client;

import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.protocol.StreamEvent;
import java.util.function.*;

/** Caller-owned process-local options. No field is serialized onto the wire. */
public final class ClientRunOptions {
    Consumer<StreamEvent> events;
    StopSignal stop = StopSignal.none();
    Object toolContext;

    public ClientRunOptions onEvent(Consumer<StreamEvent> events) {
        this.events = events;
        return this;
    }

    public ClientRunOptions stopSignal(StopSignal stop) {
        this.stop = java.util.Objects.requireNonNull(stop);
        return this;
    }

    public ClientRunOptions toolContext(Object context) {
        this.toolContext = context;
        return this;
    }
}

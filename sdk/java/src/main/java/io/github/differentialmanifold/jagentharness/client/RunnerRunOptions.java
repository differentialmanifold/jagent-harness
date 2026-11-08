package io.github.differentialmanifold.jagentharness.client;

import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.protocol.StreamEvent;
import java.util.function.*;

/** Process-local options for one Runner job. No field is serialized onto the wire. */
final class RunnerRunOptions {
    Consumer<StreamEvent> events;
    StopSignal stop = StopSignal.none();
    Object toolContext;
    java.nio.file.Path workspace;

    public RunnerRunOptions workspace(java.nio.file.Path workspace) {
        this.workspace = workspace;
        return this;
    }

    public RunnerRunOptions onEvent(Consumer<StreamEvent> events) {
        this.events = events;
        return this;
    }

    public RunnerRunOptions stopSignal(StopSignal stop) {
        this.stop = java.util.Objects.requireNonNull(stop);
        return this;
    }

    public RunnerRunOptions toolContext(Object context) {
        this.toolContext = context;
        return this;
    }
}

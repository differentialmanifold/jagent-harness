package io.github.differentialmanifold.jagentharness.core.execution;

import io.github.differentialmanifold.jagentharness.core.agent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation is independent of a caller's progress subscription. */
class RunCancellation implements StopSignal {
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public boolean isAborted() {
        return stopped.get();
    }

    public void throwIfAborted() {
        if (isAborted()) throw new StopRequestedException();
    }

    public StopRegistration onStop(Runnable action) {
        AtomicBoolean called = new AtomicBoolean();
        Runnable once =
                () -> {
                    if (called.compareAndSet(false, true)) action.run();
                };
        listeners.add(once);
        if (isAborted()) once.run();
        return () -> listeners.remove(once);
    }

    void stop() {
        if (stopped.compareAndSet(false, true)) for (Runnable action : listeners) action.run();
    }
}

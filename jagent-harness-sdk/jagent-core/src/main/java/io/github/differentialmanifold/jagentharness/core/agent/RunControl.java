package io.github.differentialmanifold.jagentharness.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class RunControl implements StopSignal {

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final Object listenersMonitor = new Object();
    private final List<Runnable> listeners = new ArrayList<Runnable>();

    public boolean requestStop() {
        if (!stopRequested.compareAndSet(false, true)) {
            return false;
        }

        List<Runnable> callbacks;
        synchronized (listenersMonitor) {
            callbacks = new ArrayList<Runnable>(listeners);
            listeners.clear();
        }
        for (Runnable callback : callbacks) {
            runQuietly(callback);
        }
        return true;
    }

    @Override
    public boolean isAborted() {
        return stopRequested.get();
    }

    @Override
    public void throwIfAborted() {
        if (isAborted()) {
            throw new StopRequestedException();
        }
    }

    @Override
    public StopRegistration onStop(Runnable action) {
        if (action == null) {
            return () -> {
            };
        }

        synchronized (listenersMonitor) {
            if (!isAborted()) {
                listeners.add(action);
                return () -> removeListener(action);
            }
        }
        runQuietly(action);
        return () -> {
        };
    }

    private void removeListener(Runnable action) {
        synchronized (listenersMonitor) {
            listeners.remove(action);
        }
    }

    private void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
        }
    }
}

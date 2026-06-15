package io.github.differentialmanifold.jagentharness.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MutableStopSignal implements StopSignal {

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Object listenersMonitor = new Object();
    private final List<Runnable> listeners = new ArrayList<Runnable>();

    public boolean requestStop() {
        if (!stopped.compareAndSet(false, true)) {
            return false;
        }
        List<Runnable> callbacks;
        synchronized (listenersMonitor) {
            callbacks = new ArrayList<Runnable>(listeners);
            listeners.clear();
        }
        for (Runnable callback : callbacks) {
            callback.run();
        }
        return true;
    }

    @Override
    public boolean isAborted() {
        return stopped.get();
    }

    @Override
    public void throwIfAborted() {
        if (isAborted()) {
            throw new StopRequestedException();
        }
    }

    @Override
    public StopRegistration onStop(Runnable action) {
        synchronized (listenersMonitor) {
            if (!isAborted()) {
                listeners.add(action);
                return () -> removeListener(action);
            }
        }
        action.run();
        return () -> {
        };
    }

    private void removeListener(Runnable action) {
        synchronized (listenersMonitor) {
            listeners.remove(action);
        }
    }
}

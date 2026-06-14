package io.github.differentialmanifold.jagentharness.core.agent;

public interface StopSignal {

    StopSignal NONE = new StopSignal() {
        @Override
        public boolean isAborted() {
            return false;
        }

        @Override
        public void throwIfAborted() {
        }

        @Override
        public StopRegistration onStop(Runnable action) {
            return () -> {
            };
        }
    };

    boolean isAborted();

    void throwIfAborted();

    StopRegistration onStop(Runnable action);

    static StopSignal none() {
        return NONE;
    }
}

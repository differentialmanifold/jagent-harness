package io.github.differentialmanifold.jagentharness.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Measures consecutive idle disconnection using a monotonic clock, including worker activity. */
final class RunnerReconnect {
    private final long timeout;
    private final LongSupplier clock;
    private boolean disconnected, counting;
    private long since;
    private int failures;

    RunnerReconnect(Duration timeout) {
        this(timeout, System::nanoTime);
    }

    RunnerReconnect(Duration timeout, LongSupplier clock) {
        if (timeout.isNegative())
            throw new IllegalArgumentException("Idle disconnect timeout must not be negative");
        this.timeout = timeout.toNanos();
        this.clock = clock;
    }

    synchronized void connected() {
        disconnected = false;
        counting = false;
        failures = 0;
    }

    synchronized void activity() {
        counting = false;
    }

    synchronized int failed(boolean idle) {
        disconnected = true;
        failures = Math.min(30, failures + 1);
        expired(idle);
        return failures;
    }

    synchronized boolean expired(boolean idle) {
        if (!idle) counting = false;
        if (!idle || !disconnected || timeout == 0) return false;
        long now = clock.getAsLong();
        if (!counting) {
            since = now;
            counting = true;
        }
        return now - since >= timeout;
    }

    synchronized long delayMillis() {
        long cap = Math.min(60000L, 1000L << Math.min(Math.max(0, failures - 1), 6));
        long delay = ThreadLocalRandom.current().nextLong(cap * 4 / 5, cap + 1);
        if (counting && timeout > 0)
            delay =
                    Math.min(
                            delay,
                            TimeUnit.NANOSECONDS.toMillis(
                                    Math.max(0, timeout - (clock.getAsLong() - since))));
        return Math.max(1, delay);
    }
}

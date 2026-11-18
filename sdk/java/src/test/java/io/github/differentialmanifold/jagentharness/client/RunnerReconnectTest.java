package io.github.differentialmanifold.jagentharness.client;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RunnerReconnectTest {
    @Test
    void requiresConsecutiveIdleDisconnectionAndResetsAfterWorkOrRecovery() {
        AtomicLong now = new AtomicLong(-1000); // nanoTime has no defined origin.
        RunnerReconnect reconnect = new RunnerReconnect(Duration.ofHours(24), now::get);
        now.addAndGet(Duration.ofDays(3).toNanos());
        assertFalse(reconnect.expired(true), "Online idleness never expires");
        reconnect.failed(true);
        now.addAndGet(Duration.ofHours(23).toNanos());
        assertFalse(reconnect.expired(true));
        reconnect.activity();
        now.addAndGet(Duration.ofDays(2).toNanos());
        assertFalse(reconnect.expired(false), "A long running task is never interrupted");
        reconnect.activity();
        assertFalse(reconnect.expired(true), "Finishing work starts a fresh idle period");
        now.addAndGet(Duration.ofHours(23).toNanos());
        reconnect.connected();
        now.addAndGet(Duration.ofDays(2).toNanos());
        assertFalse(reconnect.expired(true));
        reconnect.failed(true);
        now.addAndGet(Duration.ofHours(24).toNanos());
        assertTrue(reconnect.expired(true));
    }

    @Test
    void zeroKeepsReconnectingAndBackoffIsBounded() {
        AtomicLong now = new AtomicLong();
        RunnerReconnect reconnect = new RunnerReconnect(Duration.ZERO, now::get);
        for (int i = 0; i < 100; i++) {
            reconnect.failed(true);
            now.addAndGet(Duration.ofDays(1).toNanos());
            assertFalse(reconnect.expired(true));
            assertTrue(reconnect.delayMillis() >= 1 && reconnect.delayMillis() <= 60000);
        }
        reconnect.connected();
        reconnect.failed(true);
        assertTrue(reconnect.delayMillis() <= 1000, "Successful connection resets retry backoff");
        assertThrows(
                IllegalArgumentException.class, () -> new RunnerReconnect(Duration.ofSeconds(-1)));
    }
}

package io.github.differentialmanifold.jagentharness.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RunControlTest {

    @Test
    void requestsStopOnceAndRunsRegisteredCallbacks() {
        RunControl control = new RunControl();
        AtomicInteger callbacks = new AtomicInteger();
        control.onStop(callbacks::incrementAndGet);

        assertTrue(control.requestStop());
        assertFalse(control.requestStop());
        assertTrue(control.isAborted());
        assertEquals(1, callbacks.get());
        assertThrows(StopRequestedException.class, control::throwIfAborted);
    }

    @Test
    void runsLateCallbackImmediatelyAndAllowsRegistrationRemoval() {
        RunControl control = new RunControl();
        AtomicInteger callbacks = new AtomicInteger();
        StopRegistration registration = control.onStop(callbacks::incrementAndGet);
        registration.close();

        control.requestStop();
        assertEquals(0, callbacks.get());

        control.onStop(callbacks::incrementAndGet);
        assertEquals(1, callbacks.get());
    }
}

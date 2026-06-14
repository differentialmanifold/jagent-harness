package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.differentialmanifold.jagentharness.core.agent.RunControl;
import org.junit.jupiter.api.Test;

class AgentRunRegistryTest {

    @Test
    void registersStopsAndRemovesRunByRequestId() {
        AgentRunRegistry registry = new AgentRunRegistry();
        RunControl control = registry.register("request-1", "session-1");

        assertTrue(registry.isActive("request-1"));
        assertEquals("session-1", registry.sessionId("request-1"));
        assertTrue(registry.requestStop("request-1"));
        assertTrue(control.isAborted());
        assertFalse(registry.requestStop("request-1"));

        registry.remove("request-1", control);
        assertFalse(registry.isActive("request-1"));
        assertFalse(registry.requestStop("request-1"));
    }

    @Test
    void rejectsDuplicateActiveRequestId() {
        AgentRunRegistry registry = new AgentRunRegistry();
        registry.register("request-1", "session-1");

        assertThrows(
                ActiveRequestException.class,
                () -> registry.register("request-1", "session-2"));
    }
}

package io.github.differentialmanifold.jagentharness.example.coding;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The business runtime must not transitively load the server or its persistence layer. */
class JAgentHarnessApplicationTest {
    @Test
    void clientHasNoServerRuntime() {
        assertThrows(
                ClassNotFoundException.class,
                () ->
                        Class.forName(
                                "io.github.differentialmanifold.jagentharness.core.agent.AgentRunner"));
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("org.springframework.jdbc.core.JdbcTemplate"));
        assertDoesNotThrow(
                () ->
                        Class.forName(
                                "io.github.differentialmanifold.jagentharness.client.AgentClient"));
    }
}

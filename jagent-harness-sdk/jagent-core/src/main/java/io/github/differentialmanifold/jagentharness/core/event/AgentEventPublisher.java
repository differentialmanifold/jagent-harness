package io.github.differentialmanifold.jagentharness.core.event;

import java.util.function.Consumer;
import java.util.function.Supplier;


public interface AgentEventPublisher {

    AgentEvent publish(String sessionId, String turnId, String type, Object payload);

    default <T> T withEventConsumer(Consumer<AgentEvent> eventConsumer, Supplier<T> action) {
        return action.get();
    }
}

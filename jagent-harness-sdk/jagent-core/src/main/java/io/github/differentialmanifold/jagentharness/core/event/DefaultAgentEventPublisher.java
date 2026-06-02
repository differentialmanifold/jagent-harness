package io.github.differentialmanifold.jagentharness.core.event;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

public class DefaultAgentEventPublisher implements AgentEventPublisher {

    private final ObjectMapper objectMapper;
    private final Supplier<List<AgentEventListener>> listenersSupplier;
    private final ThreadLocal<Consumer<AgentEvent>> scopedConsumer = new ThreadLocal<Consumer<AgentEvent>>();

    public DefaultAgentEventPublisher(ObjectMapper objectMapper) {
        this(objectMapper, Collections.<AgentEventListener>emptyList());
    }

    public DefaultAgentEventPublisher(ObjectMapper objectMapper, List<AgentEventListener> listeners) {
        this(objectMapper, new StaticListenersSupplier(listeners));
    }

    public DefaultAgentEventPublisher(ObjectMapper objectMapper,
                                      Supplier<List<AgentEventListener>> listenersSupplier) {
        this.objectMapper = configureObjectMapper(objectMapper);
        this.listenersSupplier = listenersSupplier;
    }

    @Override
    public AgentEvent publish(String sessionId, String turnId, String type, Object payload) {
        String payloadJson = writePayload(payload);
        AgentEvent event = AgentEvent.of(sessionId, turnId, type, payloadJson);
        Consumer<AgentEvent> consumer = scopedConsumer.get();
        if (consumer != null) {
            consumer.accept(event);
        }
        for (AgentEventListener listener : currentListeners()) {
            listener.onEvent(event);
        }
        return event;
    }

    @Override
    public <T> T withEventConsumer(Consumer<AgentEvent> eventConsumer, Supplier<T> action) {
        if (eventConsumer == null) {
            return action.get();
        }
        scopedConsumer.set(eventConsumer);
        try {
            return action.get();
        } finally {
            scopedConsumer.remove();
        }
    }

    private List<AgentEventListener> currentListeners() {
        List<AgentEventListener> listeners = listenersSupplier == null ? null : listenersSupplier.get();
        return listeners == null
                ? Collections.<AgentEventListener>emptyList()
                : new ArrayList<AgentEventListener>(listeners);
    }

    private String writePayload(Object payload) {
        if (payload == null) {
            return "{}";
        }
        if (payload instanceof String) {
            return (String) payload;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }
    }

    private ObjectMapper configureObjectMapper(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper.copy();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.toString());
            }
        });
        mapper.registerModule(module);
        return mapper;
    }

    private static class StaticListenersSupplier implements Supplier<List<AgentEventListener>> {
        private final List<AgentEventListener> listeners;

        private StaticListenersSupplier(List<AgentEventListener> listeners) {
            this.listeners = listeners == null
                    ? Collections.<AgentEventListener>emptyList()
                    : new ArrayList<AgentEventListener>(listeners);
        }

        @Override
        public List<AgentEventListener> get() {
            return listeners;
        }
    }
}

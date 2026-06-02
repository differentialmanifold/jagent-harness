package io.github.differentialmanifold.jagentharness.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DefaultAgentEventPublisherTest {

    @Test
    void publishesCustomEventToScopedConsumerAndListeners() {
        List<AgentEvent> listenerEvents = new ArrayList<AgentEvent>();
        List<AgentEvent> scopedEvents = new ArrayList<AgentEvent>();
        DefaultAgentEventPublisher publisher = new DefaultAgentEventPublisher(
                new ObjectMapper(),
                Collections.<AgentEventListener>singletonList(listenerEvents::add));

        publisher.withEventConsumer(scopedEvents::add, () -> {
            publisher.publish("s1", "t1", "plugin.progress", payload("message", "Half done"));
            return null;
        });

        assertEquals(1, scopedEvents.size());
        assertEquals(1, listenerEvents.size());
        assertEquals("plugin.progress", scopedEvents.get(0).getType());
        assertEquals(scopedEvents.get(0).getEventId(), listenerEvents.get(0).getEventId());
    }

    @Test
    void beanPublishedEventUsesCurrentScopedConsumer() {
        List<AgentEvent> scopedEvents = new ArrayList<AgentEvent>();
        DefaultAgentEventPublisher publisher = new DefaultAgentEventPublisher(new ObjectMapper());
        CustomEventSource eventSource = new CustomEventSource(publisher);

        publisher.withEventConsumer(scopedEvents::add, () -> {
            eventSource.publishStatus("s1", "t1", "plugin.status", payload("message", "Ready"));
            return null;
        });

        assertEquals(1, scopedEvents.size());
        assertEquals("plugin.status", scopedEvents.get(0).getType());
    }

    private static class CustomEventSource {
        private final AgentEventPublisher publisher;

        private CustomEventSource(AgentEventPublisher publisher) {
            this.publisher = publisher;
        }

        private void publishStatus(String sessionId, String turnId, String type, Map<String, Object> payload) {
            publisher.publish(sessionId, turnId, type, payload);
        }
    }

    private static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(key, value);
        return payload;
    }
}

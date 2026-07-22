package io.github.differentialmanifold.jagentharness.core.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import org.junit.jupiter.api.Test;

class TimelineEventRecorderTest {

    @Test
    void persistsLightweightRunBoundariesAndSkipsStreamingEvents() {
        List<AgentEvent> storedEvents = new ArrayList<AgentEvent>();
        TimelineEventRecorder recorder = new TimelineEventRecorder(storedEvents::add);
        AgentEvent start = event(AgentEvent.AGENT_START, "{\"traceId\":\"trace-1\"}");
        AgentEvent update = event(AgentEvent.MESSAGE_UPDATE, "{\"delta\":\"partial\"}");
        AgentEvent messageEnd = event(AgentEvent.MESSAGE_END, "{\"message\":{}}");
        AgentEvent end = event(AgentEvent.AGENT_END, "{\"answer\":\"complete answer\"}");

        recorder.onEvent(start);
        recorder.onEvent(update);
        recorder.onEvent(messageEnd);
        recorder.onEvent(end);

        assertEquals(Arrays.asList(
                        AgentEvent.AGENT_START,
                        AgentEvent.MESSAGE_END,
                        AgentEvent.AGENT_END),
                eventTypes(storedEvents));
        assertNull(storedEvents.get(0).getPayloadJson());
        assertSame(messageEnd, storedEvents.get(1));
        assertNull(storedEvents.get(2).getPayloadJson());
        assertEquals("{\"traceId\":\"trace-1\"}", start.getPayloadJson());
        assertEquals("{\"answer\":\"complete answer\"}", end.getPayloadJson());
        assertEquals(start.getCreatedAt(), storedEvents.get(0).getCreatedAt());
        assertEquals(end.getCreatedAt(), storedEvents.get(2).getCreatedAt());
    }

    private AgentEvent event(String type, String payloadJson) {
        return AgentEvent.of("session-1", "run-1", null, type, payloadJson);
    }

    private List<String> eventTypes(List<AgentEvent> events) {
        List<String> types = new ArrayList<String>();
        for (AgentEvent event : events) {
            types.add(event.getType());
        }
        return types;
    }
}

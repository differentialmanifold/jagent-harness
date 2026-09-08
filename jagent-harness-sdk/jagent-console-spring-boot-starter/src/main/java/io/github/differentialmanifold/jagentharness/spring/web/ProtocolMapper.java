package io.github.differentialmanifold.jagentharness.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.protocol.StreamEvent;
import java.util.Map;

/** Isolates the public JSON envelope from internal event storage. */
public final class ProtocolMapper {
    private ProtocolMapper() {}

    public static StreamEvent event(AgentEvent event, ObjectMapper json) {
        StreamEvent wire = new StreamEvent();
        wire.eventId = event.getEventId();
        wire.sessionId = event.getSessionId();
        wire.runId = event.getRunId();
        wire.turnId = event.getTurnId();
        wire.type = event.getType();
        wire.createdAt = event.getCreatedAt().toString();
        try {
            if (event.getPayloadJson() != null)
                wire.payload = json.readValue(event.getPayloadJson(), Map.class);
            if (wire.payload == null) wire.payload = new java.util.LinkedHashMap<>();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid stored event " + event.getType(), ex);
        }
        return wire;
    }
}

package io.github.differentialmanifold.jagentharness.core.event;

import java.time.Instant;

import io.github.differentialmanifold.jagentharness.core.support.Ids;

public class AgentEvent {

    public static final String AGENT_START = "agent_start";
    public static final String AGENT_END = "agent_end";
    public static final String AGENT_STOPPED = "agent_stopped";
    public static final String TURN_START = "turn_start";
    public static final String TURN_END = "turn_end";
    public static final String MESSAGE_START = "message_start";
    public static final String MESSAGE_UPDATE = "message_update";
    public static final String MESSAGE_END = "message_end";
    public static final String TOOL_EXECUTION_START = "tool_execution_start";
    public static final String TOOL_EXECUTION_UPDATE = "tool_execution_update";
    public static final String TOOL_EXECUTION_END = "tool_execution_end";
    public static final String TOOL_APPROVAL_REQUESTED = "tool_approval_requested";
    public static final String TOOL_APPROVAL_RESOLVED = "tool_approval_resolved";
    public static final String COMPACTION_START = "compaction_start";
    public static final String COMPACTION_END = "compaction_end";

    private String eventId;
    private String sessionId;
    private String turnId;
    private String type;
    private String payloadJson;
    private Instant createdAt;

    public static AgentEvent of(String sessionId, String turnId, String type, String payloadJson) {
        AgentEvent event = new AgentEvent();
        event.setEventId(Ids.newId("evt"));
        event.setSessionId(sessionId);
        event.setTurnId(turnId);
        event.setType(type);
        event.setPayloadJson(payloadJson);
        event.setCreatedAt(Instant.now());
        return event;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

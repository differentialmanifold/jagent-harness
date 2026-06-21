package io.github.differentialmanifold.jagentharness.example.business.api;

import com.fasterxml.jackson.databind.JsonNode;

public class BusinessAgentEventResponse {

    private final String eventId;
    private final String sessionId;
    private final String turnId;
    private final String type;
    private final JsonNode payload;
    private final String createdAt;

    public BusinessAgentEventResponse(String eventId,
                                      String sessionId,
                                      String turnId,
                                      String type,
                                      JsonNode payload,
                                      String createdAt) {
        this.eventId = eventId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.type = type;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getType() {
        return type;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}

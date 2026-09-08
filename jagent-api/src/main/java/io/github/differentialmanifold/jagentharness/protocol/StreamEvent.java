package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** JSON wire contract. Independent of any framework and JSON library. */
public class StreamEvent {
    public String eventId;
    public String sessionId;
    public String runId;
    public String turnId;
    public String type;
    public Map<String, Object> payload = new LinkedHashMap<>();
    public String createdAt;
}

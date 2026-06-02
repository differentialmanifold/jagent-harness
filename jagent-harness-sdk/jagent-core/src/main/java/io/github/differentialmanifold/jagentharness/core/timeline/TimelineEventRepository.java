package io.github.differentialmanifold.jagentharness.core.timeline;

import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;

public interface TimelineEventRepository {

    void append(AgentEvent event);

    default List<AgentEvent> findBySessionId(String sessionId) {
        return Collections.emptyList();
    }
}

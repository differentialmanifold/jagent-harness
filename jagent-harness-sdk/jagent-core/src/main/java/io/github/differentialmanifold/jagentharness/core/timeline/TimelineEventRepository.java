package io.github.differentialmanifold.jagentharness.core.timeline;

import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import java.util.Collections;
import java.util.List;

public interface TimelineEventRepository {

    void append(AgentEvent event);

    default List<AgentEvent> findBySessionId(String sessionId) {
        return Collections.emptyList();
    }
}

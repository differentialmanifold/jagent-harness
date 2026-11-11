package io.github.differentialmanifold.jagentharness.core.timeline;

import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import java.util.Collections;
import java.util.List;

public interface TimelineEventRepository {

    void append(AgentEvent event);

    default List<AgentEvent> findBySessionId(String sessionId) {
        return Collections.emptyList();
    }

    default AgentEvent findLatestRunBoundary(String sessionId) {
        List<AgentEvent> events = findBySessionId(sessionId);
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent event = events.get(i);
            if (java.util.Arrays.asList(
                            AgentEvent.AGENT_START,
                            AgentEvent.AGENT_END,
                            AgentEvent.AGENT_ERROR,
                            AgentEvent.AGENT_STOPPED,
                            "agent_requires_action")
                    .contains(event.getType())) return event;
        }
        return null;
    }
}

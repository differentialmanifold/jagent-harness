package io.github.differentialmanifold.jagentharness.core.timeline;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventListener;

public class TimelineEventRecorder implements AgentEventListener {

    private static final Set<String> TRANSIENT_EVENT_TYPES = new HashSet<String>(Arrays.asList(
            AgentEvent.AGENT_START,
            AgentEvent.AGENT_END,
            AgentEvent.MESSAGE_START,
            AgentEvent.MESSAGE_UPDATE,
            AgentEvent.MESSAGE_REASONING_UPDATE,
            AgentEvent.CONTEXT_USAGE,
            AgentEvent.TOOL_EXECUTION_UPDATE
    ));

    private final TimelineEventRepository timelineEventStore;

    public TimelineEventRecorder(TimelineEventRepository timelineEventStore) {
        this.timelineEventStore = timelineEventStore;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (shouldPersist(event)) {
            timelineEventStore.append(event);
        }
    }

    private boolean shouldPersist(AgentEvent event) {
        return event != null
                && timelineEventStore != null
                && event.getSessionId() != null
                && !event.getSessionId().trim().isEmpty()
                && event.getType() != null
                && !TRANSIENT_EVENT_TYPES.contains(event.getType());
    }
}

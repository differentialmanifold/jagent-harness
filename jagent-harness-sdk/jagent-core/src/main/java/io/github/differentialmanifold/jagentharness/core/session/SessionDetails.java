package io.github.differentialmanifold.jagentharness.core.session;

import java.util.List;

import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsage;

public class SessionDetails {

    private SessionRecord session;
    private List<AgentEvent> events;
    private ModelCallUsage latestUsage;

    public SessionDetails(SessionRecord session, List<AgentEvent> events) {
        this.session = session;
        this.events = events;
    }

    public SessionRecord getSession() {
        return session;
    }

    public void setSession(SessionRecord session) {
        this.session = session;
    }

    public List<AgentEvent> getEvents() {
        return events;
    }

    public void setEvents(List<AgentEvent> events) {
        this.events = events;
    }

    public ModelCallUsage getLatestUsage() {
        return latestUsage;
    }

    public void setLatestUsage(ModelCallUsage latestUsage) {
        this.latestUsage = latestUsage;
    }
}

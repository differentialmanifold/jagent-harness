package io.github.differentialmanifold.jagentharness.spring.web;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.differentialmanifold.jagentharness.core.agent.RunControl;

public class AgentRunRegistry {

    private final ConcurrentMap<String, ActiveRun> activeRuns =
            new ConcurrentHashMap<String, ActiveRun>();

    public RunControl register(String requestId, String sessionId) {
        RunControl control = new RunControl();
        ActiveRun activeRun = new ActiveRun(sessionId, control);
        ActiveRun existing = activeRuns.putIfAbsent(requestId, activeRun);
        if (existing != null) {
            throw new ActiveRequestException(requestId);
        }
        return control;
    }

    public boolean requestStop(String requestId) {
        ActiveRun activeRun = activeRuns.get(requestId);
        return activeRun != null && activeRun.control.requestStop();
    }

    public void remove(String requestId, RunControl control) {
        if (requestId == null || control == null) {
            return;
        }
        ActiveRun activeRun = activeRuns.get(requestId);
        if (activeRun != null && activeRun.control == control) {
            activeRuns.remove(requestId, activeRun);
        }
    }

    public boolean isActive(String requestId) {
        return activeRuns.containsKey(requestId);
    }

    public String sessionId(String requestId) {
        ActiveRun activeRun = activeRuns.get(requestId);
        return activeRun == null ? null : activeRun.sessionId;
    }

    private static class ActiveRun {
        private final String sessionId;
        private final RunControl control;

        private ActiveRun(String sessionId, RunControl control) {
            this.sessionId = sessionId;
            this.control = control;
        }
    }
}

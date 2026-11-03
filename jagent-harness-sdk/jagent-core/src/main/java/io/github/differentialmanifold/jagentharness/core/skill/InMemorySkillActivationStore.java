package io.github.differentialmanifold.jagentharness.core.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Fallback for embedded applications. Use a persistent store to survive process restarts. */
public class InMemorySkillActivationStore implements SkillActivationStore {
    private final Map<String, Map<String, SkillActivation>> sessions = new TreeMap<>();

    @Override
    public synchronized List<SkillActivation> find(String sessionId, String scopeKey) {
        Map<String, SkillActivation> values = sessions.get(key(sessionId, scopeKey));
        return values == null ? Collections.emptyList() : new ArrayList<>(values.values());
    }

    @Override
    public synchronized void activate(
            String sessionId, String scopeKey, SkillActivation activation) {
        sessions.computeIfAbsent(key(sessionId, scopeKey), ignored -> new TreeMap<>())
                .putIfAbsent(activation.activationId(), activation);
    }

    private String key(String sessionId, String scopeKey) {
        return sessionId.length() + ":" + sessionId + scopeKey;
    }
}

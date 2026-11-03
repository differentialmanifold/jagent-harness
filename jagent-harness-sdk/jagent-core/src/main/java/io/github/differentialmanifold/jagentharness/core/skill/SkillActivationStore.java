package io.github.differentialmanifold.jagentharness.core.skill;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import java.util.List;

/**
 * Implementations must atomically insert a whole receipt, idempotently, without replacing others.
 */
public interface SkillActivationStore {
    List<SkillActivation> find(String sessionId, String scopeKey);

    void activate(String sessionId, String scopeKey, SkillActivation activation);

    static String scopeKey(AgentContext context) {
        String agent =
                context == null ? null : context.getAttribute("personalAgentId", String.class);
        if (agent != null && !agent.trim().isEmpty()) return "agent:" + agent.trim();
        String project = context == null ? null : context.getProjectId();
        return project == null || project.trim().isEmpty() ? "global" : "project:" + project.trim();
    }
}

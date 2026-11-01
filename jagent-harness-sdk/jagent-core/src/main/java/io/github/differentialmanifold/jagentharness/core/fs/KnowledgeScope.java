package io.github.differentialmanifold.jagentharness.core.fs;

import java.util.Objects;

public final class KnowledgeScope {

    public static final String GLOBAL_TYPE = "global";
    public static final String PROJECT_TYPE = "project";

    private static final KnowledgeScope GLOBAL = new KnowledgeScope(GLOBAL_TYPE, "");

    private final String type;
    private final String id;

    private KnowledgeScope(String type, String id) {
        this.type = type;
        this.id = id;
    }

    public static KnowledgeScope global() {
        return GLOBAL;
    }

    public static KnowledgeScope agentGlobal() {
        return new KnowledgeScope("agent-global", "");
    }

    /** Projects and personal Agents inherit independent defaults. */
    public static KnowledgeScope globalFor(
            io.github.differentialmanifold.jagentharness.core.agent.AgentContext context) {
        String agent =
                context == null ? null : context.getAttribute("personalAgentId", String.class);
        return agent == null || agent.trim().isEmpty() ? global() : agentGlobal();
    }

    public static KnowledgeScope project(String projectId) {
        String normalized = projectId == null ? "" : projectId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("projectId is required for project knowledge scope");
        }
        return new KnowledgeScope(PROJECT_TYPE, normalized);
    }

    public static KnowledgeScope agent(String id) {
        if (id == null || id.trim().isEmpty())
            throw new IllegalArgumentException("agentId is required");
        return new KnowledgeScope("agent", id.trim());
    }

    public static KnowledgeScope forProject(String projectId) {
        return projectId == null || projectId.trim().isEmpty() ? global() : project(projectId);
    }

    public String getType() {
        return type;
    }

    public String getId() {
        return id;
    }

    public boolean isGlobal() {
        return GLOBAL_TYPE.equals(type) || "agent-global".equals(type);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof KnowledgeScope)) return false;
        KnowledgeScope other = (KnowledgeScope) value;
        return type.equals(other.type) && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, id);
    }
}

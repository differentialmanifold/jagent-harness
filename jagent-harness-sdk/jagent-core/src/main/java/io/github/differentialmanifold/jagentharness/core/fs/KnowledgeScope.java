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

    public static KnowledgeScope project(String projectId) {
        String normalized = projectId == null ? "" : projectId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("projectId is required for project knowledge scope");
        }
        return new KnowledgeScope(PROJECT_TYPE, normalized);
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
        return GLOBAL_TYPE.equals(type);
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

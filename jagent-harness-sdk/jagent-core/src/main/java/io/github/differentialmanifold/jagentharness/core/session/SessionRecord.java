package io.github.differentialmanifold.jagentharness.core.session;

import java.time.Instant;

public class SessionRecord {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Null identifies a user project; personal Agent projects have one owner. */
    public String getOwnerAgentId() {
        try {
            return metadataJson == null
                    ? null
                    : JSON.readTree(metadataJson).path("ownerAgentId").asText(null);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Invalid session metadata", e);
        }
    }

    public void setOwnerAgentId(String agentId) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode metadata =
                    metadataJson == null
                            ? JSON.createObjectNode()
                            : (com.fasterxml.jackson.databind.node.ObjectNode)
                                    JSON.readTree(metadataJson);
            if (agentId == null || agentId.isEmpty()) metadata.remove("ownerAgentId");
            else metadata.put("ownerAgentId", agentId);
            metadataJson = JSON.writeValueAsString(metadata);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Invalid session metadata", e);
        }
    }

    public static final String STANDALONE_METADATA = "{\"kind\":\"standalone\"}";

    public boolean isStandalone() {
        return STANDALONE_METADATA.equals(metadataJson);
    }

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DELETED = "deleted";

    private String runnerId;

    public String getRunnerId() {
        return runnerId;
    }

    public void setRunnerId(String runnerId) {
        this.runnerId = runnerId;
    }

    private String sessionId;
    private String title;
    private String projectId;
    private String projectName;
    private String workspacePath;
    private String status;
    private String metadataJson;
    private Instant createdAt;
    private Instant updatedAt;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

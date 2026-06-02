package io.github.differentialmanifold.jagentharness.core.conversation;

import java.time.Instant;

public class CompactionState {

    private String sessionId;
    private String summary;
    private String cursorMessageId;
    private long version;
    private String metadataJson;
    private Instant updatedAt;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getCursorMessageId() {
        return cursorMessageId;
    }

    public void setCursorMessageId(String cursorMessageId) {
        this.cursorMessageId = cursorMessageId;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

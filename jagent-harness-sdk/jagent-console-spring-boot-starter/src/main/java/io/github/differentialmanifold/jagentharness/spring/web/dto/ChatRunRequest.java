package io.github.differentialmanifold.jagentharness.spring.web.dto;

import java.util.List;
import java.util.Map;

public class ChatRunRequest {

    private String sessionId;
    private String content;
    private List<ChatImageRequest> images;
    private String traceId;
    private String approvalMode;
    private Map<String, Object> attributes;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ChatImageRequest> getImages() {
        return images;
    }

    public void setImages(List<ChatImageRequest> images) {
        this.images = images;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getApprovalMode() {
        return approvalMode;
    }

    public void setApprovalMode(String approvalMode) {
        this.approvalMode = approvalMode;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}

package io.github.differentialmanifold.jagentharness.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.support.Ids;

public class ToolApprovalRequest {

    private final String approvalId;
    private final String toolCallId;
    private final String toolName;
    private final String title;
    private final String message;
    private final String action;
    private final String target;
    private final Map<String, Object> metadata;

    public ToolApprovalRequest(String title,
                               String message,
                               String action,
                               String target) {
        this(Ids.newId("appr"), null, null, title, message, action, target, Collections.<String, Object>emptyMap());
    }

    public ToolApprovalRequest(String approvalId,
                               String toolCallId,
                               String toolName,
                               String title,
                               String message,
                               String action,
                               String target,
                               Map<String, Object> metadata) {
        this.approvalId = isBlank(approvalId) ? Ids.newId("appr") : approvalId;
        this.toolCallId = blankToNull(toolCallId);
        this.toolName = blankToNull(toolName);
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
        this.action = action == null ? "" : action;
        this.target = target == null ? "" : target;
        this.metadata = metadata == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
    }

    public ToolApprovalRequest withToolContext(String currentToolCallId, String currentToolName) {
        return new ToolApprovalRequest(
                approvalId,
                isBlank(toolCallId) ? currentToolCallId : toolCallId,
                isBlank(toolName) ? currentToolName : toolName,
                title,
                message,
                action,
                target,
                metadata);
    }

    public String getApprovalId() {
        return approvalId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }
}

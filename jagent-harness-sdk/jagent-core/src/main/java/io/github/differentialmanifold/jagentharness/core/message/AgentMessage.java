package io.github.differentialmanifold.jagentharness.core.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;

public class AgentMessage {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";
    public static final String STOP_REASON_ABORTED = "aborted";

    private String messageId;
    private String sessionId;
    private String turnId;
    private String parentMessageId;
    private String role;
    private String content;
    private String reasoningContent;
    private String toolCallId;
    private String toolName;
    private List<ToolCall> toolCalls = new ArrayList<ToolCall>();
    private String stopReason;
    private String metadataJson;
    private Instant createdAt;

    public static AgentMessage user(String sessionId, String content) {
        AgentMessage message = base(sessionId, ROLE_USER, content);
        return message;
    }

    public static AgentMessage assistant(String sessionId, String content, List<ToolCall> toolCalls) {
        AgentMessage message = base(sessionId, ROLE_ASSISTANT, content);
        if (toolCalls != null) {
            message.setToolCalls(toolCalls);
        }
        return message;
    }

    public static AgentMessage tool(String sessionId, String toolCallId, String toolName, String content) {
        AgentMessage message = base(sessionId, ROLE_TOOL, content);
        message.setToolCallId(toolCallId);
        message.setToolName(toolName);
        return message;
    }

    private static AgentMessage base(String sessionId, String role, String content) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(Ids.newId("msg"));
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(Instant.now());
        return message;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getParentMessageId() {
        return parentMessageId;
    }

    public void setParentMessageId(String parentMessageId) {
        this.parentMessageId = parentMessageId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
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
}

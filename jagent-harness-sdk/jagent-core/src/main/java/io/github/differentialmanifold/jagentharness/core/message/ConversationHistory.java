package io.github.differentialmanifold.jagentharness.core.message;

import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import java.util.*;

public final class ConversationHistory {
    private ConversationHistory() {}

    /**
     * The last model response and its inputs/results are sufficient to validate the active batch.
     */
    public static List<AgentMessage> executionTail(List<AgentMessage> messages) {
        int start = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentMessage message = messages.get(i);
            if ("assistant".equals(message.getRole())
                    && !"failed".equals(message.getStopReason())
                    && !AgentMessage.STOP_REASON_ABORTED.equals(message.getStopReason())) {
                start = i;
                break;
            }
        }
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    public static List<AgentMessage> recent(List<AgentMessage> messages, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Message limit must be positive");
        return new ArrayList<>(
                messages.subList(Math.max(0, messages.size() - limit), messages.size()));
    }

    public static Map<String, ToolCall> pendingTools(List<AgentMessage> messages) {
        Map<String, ToolCall> pending = new LinkedHashMap<>();
        for (AgentMessage message : messages) {
            if ("assistant".equals(message.getRole()) && message.getToolCalls() != null)
                for (ToolCall call : message.getToolCalls())
                    pending.put(call.getToolCallId(), call);
            if ("tool".equals(message.getRole())) pending.remove(message.getToolCallId());
        }
        return pending;
    }

    public static AgentMessage callingMessage(List<AgentMessage> history, String callId) {
        for (AgentMessage message : history)
            if (message.getToolCalls() != null)
                for (ToolCall call : message.getToolCalls())
                    if (callId.equals(call.getToolCallId())) return message;
        throw new IllegalArgumentException("No tool call in this session: " + callId);
    }

    public static void validateInput(
            String sessionId, List<AgentMessage> history, List<AgentMessage> input) {
        if (input == null || input.isEmpty())
            throw new IllegalArgumentException("Messages are required");
        Map<String, ToolCall> pending = pendingTools(history);
        if (pending.values().stream().anyMatch(call -> !call.isClientExecution()))
            throw new IllegalArgumentException(
                    "A server tool has an uncertain execution outcome; inspect the history before continuing");
        String role = input.get(0) == null ? null : input.get(0).getRole();
        if (!"user".equals(role) && !"tool".equals(role))
            throw new IllegalArgumentException("Only user or tool input is accepted");
        if ("user".equals(role) && (input.size() != 1 || !pending.isEmpty()))
            throw new IllegalArgumentException(
                    "Finish outstanding tool calls before sending a user message");
        for (AgentMessage message : input) {
            if (message == null
                    || !role.equals(message.getRole())
                    || !sessionId.equals(message.getSessionId())
                    || message.getContent() == null)
                throw new IllegalArgumentException("Invalid conversation input");
            if ("tool".equals(role) && pending.remove(message.getToolCallId()) == null)
                throw new IllegalArgumentException(
                        "Tool result does not match an unanswered call in this session");
        }
    }
}

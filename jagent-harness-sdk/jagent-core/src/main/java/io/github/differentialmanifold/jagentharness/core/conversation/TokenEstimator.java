package io.github.differentialmanifold.jagentharness.core.conversation;

import java.util.Collection;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;

public class TokenEstimator {

    public int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 127) {
                ascii++;
            } else {
                nonAscii++;
            }
        }
        return Math.max(1, (ascii + 3) / 4 + nonAscii);
    }

    public int estimateMessages(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (AgentMessage message : messages) {
            total += estimateMessage(message);
        }
        return total;
    }

    public int estimateTools(Collection<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ToolDefinition tool : tools) {
            if (tool == null) {
                continue;
            }
            total += 16;
            total += estimateText(tool.getName());
            total += estimateText(tool.getDescription());
            total += estimateText(tool.getParametersSchema() == null ? null : tool.getParametersSchema().toString());
        }
        return total;
    }

    private int estimateMessage(AgentMessage message) {
        if (message == null) {
            return 0;
        }
        int total = 8;
        total += estimateText(message.getRole());
        total += estimateText(message.getContent());
        total += estimateText(message.getToolCallId());
        total += estimateText(message.getToolName());
        if (message.getToolCalls() != null) {
            for (ToolCall call : message.getToolCalls()) {
                total += estimateText(call.getToolCallId());
                total += estimateText(call.getName());
                total += estimateText(call.getArgumentsJson());
            }
        }
        return total;
    }
}

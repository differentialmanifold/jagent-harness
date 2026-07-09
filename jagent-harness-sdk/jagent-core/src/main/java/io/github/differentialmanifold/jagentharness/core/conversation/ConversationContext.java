package io.github.differentialmanifold.jagentharness.core.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;

public class ConversationContext {

    private final String systemPrompt;
    private final List<AgentMessage> messages;
    private final int estimatedTokens;
    private final int rawEstimatedTokens;
    private final int contextWindowTokens;
    private final int thresholdTokens;
    private final String estimateSource;

    public ConversationContext(String systemPrompt, List<AgentMessage> messages) {
        this(systemPrompt, messages, 0, 0, 0, 0, null);
    }

    public ConversationContext(String systemPrompt,
                               List<AgentMessage> messages,
                               int estimatedTokens,
                               int rawEstimatedTokens,
                               int contextWindowTokens,
                               int thresholdTokens,
                               String estimateSource) {
        this.systemPrompt = systemPrompt;
        this.messages = messages == null
                ? Collections.<AgentMessage>emptyList()
                : Collections.unmodifiableList(new ArrayList<AgentMessage>(messages));
        this.estimatedTokens = estimatedTokens;
        this.rawEstimatedTokens = rawEstimatedTokens;
        this.contextWindowTokens = contextWindowTokens;
        this.thresholdTokens = thresholdTokens;
        this.estimateSource = estimateSource;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<AgentMessage> getMessages() {
        return messages;
    }

    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    public int getRawEstimatedTokens() {
        return rawEstimatedTokens;
    }

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public int getThresholdTokens() {
        return thresholdTokens;
    }

    public String getEstimateSource() {
        return estimateSource;
    }
}

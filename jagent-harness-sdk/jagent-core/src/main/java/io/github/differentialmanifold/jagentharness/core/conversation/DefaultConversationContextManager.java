package io.github.differentialmanifold.jagentharness.core.conversation;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsage;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsageStore;
import io.github.differentialmanifold.jagentharness.core.usage.NoopModelCallUsageStore;

public class DefaultConversationContextManager implements ConversationContextManager {

    private static final String ABORTED_RESPONSE_CONTEXT =
            "The user interrupted the preceding assistant response. "
                    + "Treat it as incomplete and do not continue it unless explicitly requested.";

    private final AgentSettings settings;
    private final CompactionStore compactionStore;
    private final AgentEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ModelCallUsageStore modelCallUsageStore;
    private final TokenEstimator tokenEstimator = new TokenEstimator();

    public DefaultConversationContextManager(AgentSettings settings,
                                             CompactionStore compactionStore,
                                             AgentEventPublisher eventPublisher,
                                             ObjectMapper objectMapper) {
        this(settings, compactionStore, eventPublisher, objectMapper, new NoopModelCallUsageStore());
    }

    public DefaultConversationContextManager(AgentSettings settings,
                                             CompactionStore compactionStore,
                                             AgentEventPublisher eventPublisher,
                                             ObjectMapper objectMapper,
                                             ModelCallUsageStore modelCallUsageStore) {
        this.settings = settings;
        this.compactionStore = compactionStore;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.modelCallUsageStore = modelCallUsageStore == null ? new NoopModelCallUsageStore() : modelCallUsageStore;
    }

    @Override
    public ConversationContext prepare(ConversationContextRequest request) {
        request.getStopSignal().throwIfAborted();
        List<AgentMessage> messages = request.getMessages() == null
                ? new ArrayList<AgentMessage>()
                : new ArrayList<AgentMessage>(request.getMessages());
        CompactionState state = compactionStore.findBySessionId(request.getSessionId());
        String summary = state == null ? null : state.getSummary();
        String cursorMessageId = state == null ? null : state.getCursorMessageId();
        Instant usageBaselineNotBefore = state == null ? null : state.getUpdatedAt();
        List<AgentMessage> contextMessages = messagesAfterCursor(messages, cursorMessageId);
        String systemPromptWithSummary = appendCompactionSummary(request.getSystemPrompt(), summary);
        List<AgentMessage> modelMessages = messagesForModel(contextMessages);
        boolean usageBaselineValid = true;
        EstimateSnapshot estimate = estimateRequestTokens(
                request.getSessionId(),
                systemPromptWithSummary,
                modelMessages,
                request.getTools(),
                usageBaselineValid,
                usageBaselineNotBefore);
        int thresholdTokens = estimate.thresholdTokens;

        if (shouldCompact(estimate.estimatedTokens, thresholdTokens, contextMessages)) {
            EstimateSnapshot beforeCompactionEstimate = estimate;
            List<AgentMessage> recentMessages = recentMessages(contextMessages);
            int compactMessageCount = contextMessages.size() - recentMessages.size();
            if (compactMessageCount > 0) {
                List<AgentMessage> messagesToCompact = new ArrayList<AgentMessage>(
                        contextMessages.subList(0, compactMessageCount));
                Map<String, Object> startPayload = new LinkedHashMap<String, Object>();
                startPayload.put("estimatedTokens", estimate.estimatedTokens);
                startPayload.put("rawEstimatedTokens", estimate.rawEstimatedTokens);
                startPayload.put("thresholdTokens", thresholdTokens);
                startPayload.put("estimateSource", estimate.estimateSource);
                startPayload.put("messageCount", contextMessages.size());
                startPayload.put("compactMessageCount", messagesToCompact.size());
                startPayload.put("recentMessageCount", recentMessages.size());
                publish(request.getSessionId(), request.getTurnId(), AgentEvent.COMPACTION_START, startPayload);

                summary = compactConversation(
                        request.getProvider(),
                        summary,
                        messagesToCompact,
                        request.getStopSignal());
                request.getStopSignal().throwIfAborted();
                cursorMessageId = messagesToCompact.get(messagesToCompact.size() - 1).getMessageId();
                compactionStore.save(request.getSessionId(), summary, cursorMessageId);
                usageBaselineValid = false;

                contextMessages = recentMessages;
                systemPromptWithSummary = appendCompactionSummary(request.getSystemPrompt(), summary);
                modelMessages = messagesForModel(contextMessages);
                estimate = estimateRequestTokens(
                        request.getSessionId(),
                        systemPromptWithSummary,
                        modelMessages,
                        request.getTools(),
                        usageBaselineValid,
                        usageBaselineNotBefore);
                Map<String, Object> endPayload = new LinkedHashMap<String, Object>();
                endPayload.put("estimatedTokensBefore", beforeCompactionEstimate.estimatedTokens);
                endPayload.put("rawEstimatedTokensBefore", beforeCompactionEstimate.rawEstimatedTokens);
                endPayload.put("estimatedTokensAfter", estimate.estimatedTokens);
                endPayload.put("rawEstimatedTokensAfter", estimate.rawEstimatedTokens);
                endPayload.put("thresholdTokens", estimate.thresholdTokens);
                endPayload.put("estimateSource", estimate.estimateSource);
                endPayload.put("summaryTokens", tokenEstimator.estimateText(summary));
                endPayload.put("cursorMessageId", cursorMessageId);
                publish(request.getSessionId(), request.getTurnId(), AgentEvent.COMPACTION_END, endPayload);
            }
        }

        request.getStopSignal().throwIfAborted();
        modelMessages = messagesForModel(contextMessages);
        estimate = estimateRequestTokens(
                request.getSessionId(),
                systemPromptWithSummary,
                modelMessages,
                request.getTools(),
                usageBaselineValid,
                usageBaselineNotBefore);
        return new ConversationContext(
                systemPromptWithSummary,
                modelMessages,
                estimate.estimatedTokens,
                estimate.rawEstimatedTokens,
                estimate.contextWindowTokens,
                estimate.thresholdTokens,
                estimate.estimateSource);
    }

    private boolean shouldCompact(int estimatedTokens,
                                  int thresholdTokens,
                                  List<AgentMessage> contextMessages) {
        return settings.isCompactionEnabled()
                && thresholdTokens > 0
                && estimatedTokens >= thresholdTokens
                && contextMessages != null
                && contextMessages.size() > Math.max(1, settings.getCompactionRecentMessages());
    }

    private EstimateSnapshot estimateRequestTokens(String sessionId,
                                                   String systemPrompt,
                                                   List<AgentMessage> messages,
                                                   Collection<ToolDefinition> tools,
                                                   boolean usageBaselineValid,
                                                   Instant usageBaselineNotBefore) {
        int rawEstimatedTokens = rawEstimateRequestTokens(systemPrompt, messages, tools);
        int contextWindowTokens = effectiveContextWindowTokens();
        int thresholdTokens = compactionThresholdTokens(contextWindowTokens);
        if (usageBaselineValid) {
            ModelCallUsage latestUsage = modelCallUsageStore.findLatestBySessionId(sessionId);
            if (isUsageBaselineUsable(latestUsage, usageBaselineNotBefore)) {
                int baselineIndex = indexOfMessage(messages, latestUsage.getMessageId());
                if (baselineIndex >= 0) {
                    List<AgentMessage> deltaMessages = messages.subList(baselineIndex + 1, messages.size());
                    int estimatedTokens = latestUsage.getActualContextTokens()
                            + tokenEstimator.estimateMessages(deltaMessages);
                    return new EstimateSnapshot(
                            Math.max(1, estimatedTokens),
                            rawEstimatedTokens,
                            contextWindowTokens,
                            thresholdTokens,
                            ModelCallUsage.ESTIMATE_SOURCE_ACTUAL_BASELINE);
                }
            }
        }
        return new EstimateSnapshot(
                rawEstimatedTokens,
                rawEstimatedTokens,
                contextWindowTokens,
                thresholdTokens,
                ModelCallUsage.ESTIMATE_SOURCE_FULL);
    }

    private boolean isUsageBaselineUsable(ModelCallUsage usage, Instant usageBaselineNotBefore) {
        if (usage == null || usage.getActualContextTokens() == null) {
            return false;
        }
        if (usageBaselineNotBefore == null) {
            return true;
        }
        return usage.getCreatedAt() != null && !usage.getCreatedAt().isBefore(usageBaselineNotBefore);
    }

    private int rawEstimateRequestTokens(String systemPrompt,
                                         List<AgentMessage> messages,
                                         Collection<ToolDefinition> tools) {
        return tokenEstimator.estimateText(systemPrompt)
                + tokenEstimator.estimateMessages(messages)
                + tokenEstimator.estimateTools(tools);
    }

    private int effectiveContextWindowTokens() {
        return settings.getContextWindowTokens() <= 0
                ? 128000
                : settings.getContextWindowTokens();
    }

    private int compactionThresholdTokens(int contextWindowTokens) {
        double ratio = settings.getCompactionThresholdRatio() <= 0
                ? 0.8d
                : settings.getCompactionThresholdRatio();
        if (ratio > 1.0d) {
            ratio = 1.0d;
        }
        return Math.max(1, (int) Math.floor(contextWindowTokens * ratio));
    }

    private int indexOfMessage(List<AgentMessage> messages, String messageId) {
        if (messages == null || messageId == null || messageId.trim().isEmpty()) {
            return -1;
        }
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            if (messageId.equals(message == null ? null : message.getMessageId())) {
                return i;
            }
        }
        return -1;
    }

    private List<AgentMessage> messagesAfterCursor(List<AgentMessage> messages, String cursorMessageId) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<AgentMessage>();
        }
        if (cursorMessageId == null || cursorMessageId.trim().isEmpty()) {
            return new ArrayList<AgentMessage>(messages);
        }

        for (int i = 0; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            if (cursorMessageId.equals(message.getMessageId())) {
                return new ArrayList<AgentMessage>(messages.subList(i + 1, messages.size()));
            }
        }
        return new ArrayList<AgentMessage>(messages);
    }

    private List<AgentMessage> recentMessages(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<AgentMessage>();
        }
        int recentCount = settings.getCompactionRecentMessages() <= 0
                ? 20
                : settings.getCompactionRecentMessages();
        int start = Math.max(0, messages.size() - recentCount);
        while (start > 0 && AgentMessage.ROLE_TOOL.equals(messages.get(start).getRole())) {
            start--;
        }
        return new ArrayList<AgentMessage>(messages.subList(start, messages.size()));
    }

    private String compactConversation(ModelProvider provider,
                                       String previousSummary,
                                       List<AgentMessage> messagesToCompact,
                                       StopSignal stopSignal) {
        ModelRequest request = new ModelRequest();
        request.setModel(settings.getModel());
        request.setSystemPrompt(compactionSystemPrompt());
        request.setMessages(Collections.singletonList(AgentMessage.user(
                "compaction",
                compactionUserPrompt(previousSummary, messagesToCompact))));
        request.setTools(Collections.<ToolDefinition>emptyList());

        ModelResponse response = provider.chat(request, (java.util.function.Consumer<String>) null, stopSignal);
        String summary = response == null ? null : response.getContent();
        if (summary == null || summary.trim().isEmpty()) {
            return previousSummary == null ? "" : previousSummary;
        }
        return summary.trim();
    }

    private String compactionSystemPrompt() {
        int targetTokens = settings.getCompactionTargetTokens() <= 0
                ? 4000
                : settings.getCompactionTargetTokens();
        return "You compact long agent conversations for future context. "
                + "Produce a concise but complete summary under about " + targetTokens + " tokens. "
                + "Preserve durable facts, user intent, decisions, constraints, file paths, code changes, "
                + "tool results, unresolved tasks, and current next steps. "
                + "Do not invent details. Do not include generic filler.";
    }

    private String compactionUserPrompt(String previousSummary, List<AgentMessage> messagesToCompact) {
        StringBuilder prompt = new StringBuilder();
        if (previousSummary != null && !previousSummary.trim().isEmpty()) {
            prompt.append("Previous compacted summary:\n")
                    .append(previousSummary.trim())
                    .append("\n\n");
        }
        prompt.append("Compact this conversation segment and merge it with the previous summary if present.\n\n");
        prompt.append(renderMessagesForCompaction(messagesToCompact));
        return prompt.toString();
    }

    private String renderMessagesForCompaction(List<AgentMessage> messages) {
        StringBuilder rendered = new StringBuilder();
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (AgentMessage message : messages) {
            rendered.append("[").append(valueOrEmpty(message.getRole())).append("]");
            if (message.getStopReason() != null && !message.getStopReason().trim().isEmpty()) {
                rendered.append(" stopReason=").append(message.getStopReason());
            }
            if (message.getToolName() != null && !message.getToolName().trim().isEmpty()) {
                rendered.append(" tool=").append(message.getToolName());
            }
            if (message.getToolCallId() != null && !message.getToolCallId().trim().isEmpty()) {
                rendered.append(" toolCallId=").append(message.getToolCallId());
            }
            rendered.append("\n");
            if (message.getContent() != null && !message.getContent().isEmpty()) {
                rendered.append(message.getContent()).append("\n");
            }
            if (isAbortedAssistant(message)) {
                rendered.append("The user interrupted this assistant response before completion.\n");
            }
            if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                try {
                    rendered.append("toolCalls=")
                            .append(objectMapper.writeValueAsString(message.getToolCalls()))
                            .append("\n");
                } catch (IOException e) {
                    rendered.append("toolCalls=<failed to serialize>\n");
                }
            }
            rendered.append("\n");
        }
        return rendered.toString();
    }

    private List<AgentMessage> messagesForModel(List<AgentMessage> messages) {
        List<AgentMessage> modelMessages = new ArrayList<AgentMessage>();
        if (messages == null || messages.isEmpty()) {
            return modelMessages;
        }
        for (AgentMessage message : messages) {
            if (!isAbortedAssistant(message)) {
                modelMessages.add(message);
                continue;
            }
            if (hasAssistantContent(message)) {
                modelMessages.add(message);
            }
            AgentMessage interrupted = AgentMessage.user(message.getSessionId(), ABORTED_RESPONSE_CONTEXT);
            interrupted.setTurnId(message.getTurnId());
            interrupted.setParentMessageId(message.getMessageId());
            modelMessages.add(interrupted);
        }
        return modelMessages;
    }

    private boolean isAbortedAssistant(AgentMessage message) {
        return message != null
                && AgentMessage.ROLE_ASSISTANT.equals(message.getRole())
                && AgentMessage.STOP_REASON_ABORTED.equals(message.getStopReason());
    }

    private boolean hasAssistantContent(AgentMessage message) {
        return (message.getContent() != null && !message.getContent().isEmpty())
                || (message.getToolCalls() != null && !message.getToolCalls().isEmpty());
    }

    private String appendCompactionSummary(String systemPrompt, String summary) {
        String prompt = systemPrompt == null ? "" : systemPrompt;
        if (summary == null || summary.trim().isEmpty()) {
            return prompt;
        }
        return prompt
                + "\n\n## Compacted Conversation Summary\n"
                + "The following summary condenses earlier messages that are no longer included verbatim. "
                + "Use it as durable context while prioritizing newer messages when there is a conflict.\n\n"
                + summary.trim();
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private AgentEvent publish(String sessionId,
                               String turnId,
                               String type,
                               Object payload) {
        return eventPublisher.publish(sessionId, turnId, type, payload);
    }

    private static class EstimateSnapshot {
        private final int estimatedTokens;
        private final int rawEstimatedTokens;
        private final int contextWindowTokens;
        private final int thresholdTokens;
        private final String estimateSource;

        private EstimateSnapshot(int estimatedTokens,
                                 int rawEstimatedTokens,
                                 int contextWindowTokens,
                                 int thresholdTokens,
                                 String estimateSource) {
            this.estimatedTokens = estimatedTokens;
            this.rawEstimatedTokens = rawEstimatedTokens;
            this.contextWindowTokens = contextWindowTokens;
            this.thresholdTokens = thresholdTokens;
            this.estimateSource = estimateSource;
        }
    }
}

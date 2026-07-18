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
import io.github.differentialmanifold.jagentharness.core.agent.ModelCallRetryExecutor;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelDeltaConsumer;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsage;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsageStore;
import io.github.differentialmanifold.jagentharness.core.usage.NoopModelCallUsageStore;

public class DefaultConversationContextManager implements ConversationContextManager {

    private static final int MAX_TOOL_RESULT_CHARS = 2000;
    private static final String TOOL_RESULT_TRUNCATED_SUFFIX = "\n...[tool result truncated]";
    private static final String COMPACTION_SUMMARY_HEADER =
            "\n\n## Compacted Conversation Summary\n"
                    + "The following summary condenses earlier messages that are no longer included verbatim. "
                    + "Use it as durable context while prioritizing newer messages when there is a conflict.\n\n";
    private static final String ABORTED_RESPONSE_CONTEXT =
            "The user interrupted the preceding assistant response. "
                    + "Treat it as incomplete and do not continue it unless explicitly requested.";

    private final AgentSettings settings;
    private final CompactionStore compactionStore;
    private final AgentEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ModelCallUsageStore modelCallUsageStore;
    private final ModelCallRetryExecutor modelCallRetryExecutor;
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
        this.modelCallRetryExecutor = new ModelCallRetryExecutor(settings, eventPublisher);
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
            RecentSelection recentSelection = selectRecentMessages(contextMessages);
            List<AgentMessage> recentMessages = recentSelection.messages;
            int compactMessageCount = recentSelection.startIndex;
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
                startPayload.put("compactTurnCount", recentSelection.compactTurnCount);
                startPayload.put("recentTurnCount", recentSelection.recentTurnCount);
                publish(request.getSessionId(), request.getRunId(), request.getTurnId(),
                        AgentEvent.COMPACTION_START, startPayload);

                summary = compactConversation(
                        request.getProvider(),
                        summary,
                        messagesToCompact,
                        request.getStopSignal(),
                        request.getSessionId(),
                        request.getRunId(),
                        request.getTurnId());
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
                publish(request.getSessionId(), request.getRunId(), request.getTurnId(),
                        AgentEvent.COMPACTION_END, endPayload);
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
                && contextMessages.size() > Math.max(1, effectiveRecentMessages());
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

    private int effectiveRecentMessages() {
        return settings.getCompactionRecentMessages() <= 0
                ? 20
                : settings.getCompactionRecentMessages();
    }

    private RecentSelection selectRecentMessages(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new RecentSelection(
                    0,
                    new ArrayList<AgentMessage>(),
                    0,
                    0);
        }
        List<TurnRange> turns = turnRanges(messages);
        int startIndex = Math.max(0, messages.size() - effectiveRecentMessages());
        for (TurnRange turn : turns) {
            if (startIndex >= turn.startIndex && startIndex < turn.endIndex) {
                startIndex = turn.startIndex;
                break;
            }
        }
        int compactTurnCount = 0;
        int recentTurnCount = 0;
        for (TurnRange turn : turns) {
            if (turn.endIndex <= startIndex) {
                compactTurnCount++;
            } else {
                recentTurnCount++;
            }
        }
        return new RecentSelection(
                startIndex,
                new ArrayList<AgentMessage>(messages.subList(startIndex, messages.size())),
                compactTurnCount,
                recentTurnCount);
    }

    private List<TurnRange> turnRanges(List<AgentMessage> messages) {
        List<TurnRange> turns = new ArrayList<TurnRange>();
        if (messages == null || messages.isEmpty()) {
            return turns;
        }
        int startIndex = 0;
        String currentTurnId = normalizedTurnId(messages.get(0));
        for (int i = 1; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            String nextTurnId = normalizedTurnId(message);
            boolean explicitTurnChanged = currentTurnId != null || nextTurnId != null
                    ? !sameValue(currentTurnId, nextTurnId)
                    : false;
            boolean legacyTurnStarted = currentTurnId == null
                    && nextTurnId == null
                    && AgentMessage.ROLE_USER.equals(message.getRole());
            if (explicitTurnChanged || legacyTurnStarted) {
                turns.add(new TurnRange(startIndex, i));
                startIndex = i;
                currentTurnId = nextTurnId;
            }
        }
        turns.add(new TurnRange(startIndex, messages.size()));
        return turns;
    }

    private String normalizedTurnId(AgentMessage message) {
        if (message == null || message.getTurnId() == null || message.getTurnId().trim().isEmpty()) {
            return null;
        }
        return message.getTurnId().trim();
    }

    private boolean sameValue(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean isOversizedToolResult(AgentMessage message) {
        return message != null
                && AgentMessage.ROLE_TOOL.equals(message.getRole())
                && message.getContent() != null
                && message.getContent().length() > MAX_TOOL_RESULT_CHARS;
    }

    private String truncatedToolResult(String content) {
        int prefixLength = MAX_TOOL_RESULT_CHARS - TOOL_RESULT_TRUNCATED_SUFFIX.length();
        return content.substring(0, prefixLength) + TOOL_RESULT_TRUNCATED_SUFFIX;
    }

    private String compactConversation(ModelProvider provider,
                                       String previousSummary,
                                       List<AgentMessage> messagesToCompact,
                                       StopSignal stopSignal,
                                       String sessionId,
                                       String runId,
                                       String turnId) {
        ModelRequest request = new ModelRequest();
        request.setModel(settings.getModel());
        request.setSystemPrompt(compactionSystemPrompt());
        request.setMessages(Collections.singletonList(AgentMessage.user(
                "compaction",
                compactionUserPrompt(previousSummary, messagesToCompact))));
        request.setTools(Collections.<ToolDefinition>emptyList());

        ModelResponse response = modelCallRetryExecutor.call(
                provider,
                request,
                (ModelDeltaConsumer) null,
                null,
                stopSignal,
                sessionId,
                runId,
                turnId);
        String summary = response == null ? null : response.getContent();
        if (summary == null || summary.trim().isEmpty()) {
            throw new ModelProviderException("Compaction returned an empty summary", null, false);
        }
        return summary.trim();
    }

    private int effectiveCompactionTargetTokens() {
        return settings.getCompactionTargetTokens() <= 0
                ? 4000
                : settings.getCompactionTargetTokens();
    }

    private String compactionSystemPrompt() {
        return "You compact long agent conversations for future context. "
                + "Produce a concise but complete summary under about "
                + effectiveCompactionTargetTokens() + " tokens. "
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
                String content = isOversizedToolResult(message)
                        ? truncatedToolResult(message.getContent())
                        : message.getContent();
                rendered.append(content).append("\n");
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
            interrupted.setRunId(message.getRunId());
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
        return prompt + COMPACTION_SUMMARY_HEADER + summary.trim();
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private AgentEvent publish(String sessionId,
                               String runId,
                               String turnId,
                               String type,
                               Object payload) {
        return eventPublisher.publish(sessionId, runId, turnId, type, payload);
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

    private static class TurnRange {
        private final int startIndex;
        private final int endIndex;

        private TurnRange(int startIndex, int endIndex) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }
    }

    private static class RecentSelection {
        private final int startIndex;
        private final List<AgentMessage> messages;
        private final int compactTurnCount;
        private final int recentTurnCount;

        private RecentSelection(int startIndex,
                                List<AgentMessage> messages,
                                int compactTurnCount,
                                int recentTurnCount) {
            this.startIndex = startIndex;
            this.messages = messages;
            this.compactTurnCount = compactTurnCount;
            this.recentTurnCount = recentTurnCount;
        }
    }
}

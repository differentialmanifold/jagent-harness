package io.github.differentialmanifold.jagentharness.core.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.event.DefaultAgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelDeltaConsumer;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsage;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsageStore;
import org.junit.jupiter.api.Test;

class DefaultConversationContextManagerTest {

    @Test
    void convertsAbortedAssistantMessageIntoModelContext() {
        DefaultConversationContextManager manager = manager();
        AgentMessage originalUser = AgentMessage.user("s1", "Start a long answer");
        AgentMessage interrupted = AgentMessage.assistant(
                "s1",
                "Partial answer",
                Collections.emptyList());
        interrupted.setStopReason(AgentMessage.STOP_REASON_ABORTED);
        AgentMessage followUp = AgentMessage.user("s1", "Now answer something else");

        ConversationContext context = manager.prepare(request(
                Arrays.asList(originalUser, interrupted, followUp)));

        List<AgentMessage> messages = context.getMessages();
        assertEquals(4, messages.size());
        assertEquals(originalUser, messages.get(0));
        assertEquals(interrupted, messages.get(1));
        assertEquals(AgentMessage.ROLE_USER, messages.get(2).getRole());
        assertTrue(messages.get(2).getContent().contains("interrupted"));
        assertTrue(messages.get(2).getContent().contains("incomplete"));
        assertEquals(followUp, messages.get(3));
    }

    @Test
    void omitsEmptyAbortedAssistantContentButKeepsInterruptionContext() {
        DefaultConversationContextManager manager = manager();
        AgentMessage originalUser = AgentMessage.user("s1", "Start a long answer");
        AgentMessage interrupted = AgentMessage.assistant("s1", "", Collections.emptyList());
        interrupted.setStopReason(AgentMessage.STOP_REASON_ABORTED);
        AgentMessage followUp = AgentMessage.user("s1", "Continue with another task");

        ConversationContext context = manager.prepare(request(
                Arrays.asList(originalUser, interrupted, followUp)));

        List<AgentMessage> messages = context.getMessages();
        assertEquals(3, messages.size());
        assertFalse(messages.contains(interrupted));
        assertEquals(AgentMessage.ROLE_USER, messages.get(1).getRole());
        assertTrue(messages.get(1).getContent().contains("interrupted"));
        assertEquals(followUp, messages.get(2));
    }

    @Test
    void compactionUsesPersistedCursorAndPreservesAbortReason() {
        AgentSettings settings = new AgentSettings();
        settings.setCompactionEnabled(true);
        settings.setContextWindowTokens(1);
        settings.setCompactionThresholdRatio(1.0d);
        settings.setCompactionRecentMessages(1);
        FakeCompactionStore store = new FakeCompactionStore();
        CapturingModelProvider provider = new CapturingModelProvider();
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultConversationContextManager manager = new DefaultConversationContextManager(
                settings,
                store,
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper);

        AgentMessage originalUser = AgentMessage.user("s1", "Start a long answer");
        AgentMessage interrupted = AgentMessage.assistant("s1", "Partial answer", Collections.emptyList());
        interrupted.setStopReason(AgentMessage.STOP_REASON_ABORTED);
        AgentMessage followUp = AgentMessage.user("s1", "Start another task");

        ConversationContext context = manager.prepare(new ConversationContextRequest(
                "s1",
                "r1",
                "t1",
                "System prompt",
                Arrays.asList(originalUser, interrupted, followUp),
                Collections.<ToolDefinition>emptyList(),
                provider));

        assertEquals(interrupted.getMessageId(), store.cursorMessageId);
        assertTrue(provider.compactionPrompt.contains("stopReason=aborted"));
        assertTrue(provider.compactionPrompt.contains("interrupted this assistant response"));
        assertEquals(1, context.getMessages().size());
        assertEquals(followUp, context.getMessages().get(0));
    }

    @Test
    void doesNotAdvanceCompactionCursorWhenSummaryIsEmpty() {
        AgentSettings settings = compactingSettings();
        FakeCompactionStore store = new FakeCompactionStore();
        CapturingModelProvider provider = new CapturingModelProvider();
        provider.summary = "  ";
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultConversationContextManager manager = new DefaultConversationContextManager(
                settings,
                store,
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper);

        List<AgentMessage> messages = Arrays.asList(
                turnMessage("m1", "turn-1", AgentMessage.ROLE_USER, repeated("old context ", 100)),
                turnMessage("m2", "turn-1", AgentMessage.ROLE_ASSISTANT, "old response"),
                turnMessage("m3", "turn-2", AgentMessage.ROLE_USER, "current request"));

        ModelProviderException error = assertThrows(
                ModelProviderException.class,
                () -> manager.prepare(request(messages, provider)));

        assertTrue(error.getMessage().contains("empty summary"));
        assertEquals(0, store.saveCount);
        assertEquals(null, store.cursorMessageId);
        assertEquals(null, store.summary);
    }

    @Test
    void preservesConfiguredRecentMessagesWithoutSplittingTurns() {
        AgentSettings settings = compactingSettings();
        FakeCompactionStore store = new FakeCompactionStore();
        CapturingModelProvider provider = new CapturingModelProvider();
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultConversationContextManager manager = new DefaultConversationContextManager(
                settings,
                store,
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper);

        AgentMessage firstUser = turnMessage(
                "m1", "turn-1", AgentMessage.ROLE_USER, repeated("first turn ", 100));
        AgentMessage firstAssistant = turnMessage(
                "m2", "turn-1", AgentMessage.ROLE_ASSISTANT, "first answer");
        AgentMessage largeUser = turnMessage(
                "m3", "turn-2", AgentMessage.ROLE_USER, repeated("large middle turn ", 100));
        AgentMessage largeAssistant = turnMessage(
                "m4", "turn-2", AgentMessage.ROLE_ASSISTANT, "middle answer");
        AgentMessage recentUser = turnMessage(
                "m5", "turn-3", AgentMessage.ROLE_USER, "recent question");
        AgentMessage recentAssistant = turnMessage(
                "m6", "turn-3", AgentMessage.ROLE_ASSISTANT, "recent answer");

        ConversationContext context = manager.prepare(request(
                Arrays.asList(
                        firstUser,
                        firstAssistant,
                        largeUser,
                        largeAssistant,
                        recentUser,
                        recentAssistant),
                provider));

        assertEquals("m4", store.cursorMessageId);
        assertTrue(provider.compactionPrompt.contains("first turn"));
        assertTrue(provider.compactionPrompt.contains("large middle turn"));
        assertEquals(2, context.getMessages().size());
        assertEquals("turn-3", context.getMessages().get(0).getTurnId());
        assertEquals("turn-3", context.getMessages().get(1).getTurnId());
    }

    @Test
    void doesNotCompactWhenOnlyOneCompleteTurnExists() {
        AgentSettings settings = compactingSettings();
        FakeCompactionStore store = new FakeCompactionStore();
        CapturingModelProvider provider = new CapturingModelProvider();
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultConversationContextManager manager = new DefaultConversationContextManager(
                settings,
                store,
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper);

        List<AgentMessage> messages = Arrays.asList(
                turnMessage("m1", "turn-1", AgentMessage.ROLE_USER, repeated("large request ", 100)),
                turnMessage("m2", "turn-1", AgentMessage.ROLE_ASSISTANT, repeated("large answer ", 100)));

        ConversationContext context = manager.prepare(request(messages, provider));

        assertEquals(0, store.saveCount);
        assertEquals(0, provider.attempts.get());
        assertEquals(2, context.getMessages().size());
    }

    @Test
    void keepsOversizedToolResultInNormalModelContext() {
        AgentSettings settings = new AgentSettings();
        settings.setCompactionEnabled(false);
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultConversationContextManager manager = new DefaultConversationContextManager(
                settings,
                new NoopCompactionStore(),
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper);
        AgentMessage toolResult = turnMessage(
                "m2", "turn-1", AgentMessage.ROLE_TOOL, repeated("x", 2500));
        toolResult.setToolName("read");
        toolResult.setToolCallId("call-1");

        ConversationContext context = manager.prepare(request(Collections.singletonList(toolResult)));

        AgentMessage modelToolResult = context.getMessages().get(0);
        assertEquals(2500, modelToolResult.getContent().length());
        assertEquals(2500, toolResult.getContent().length());
        assertEquals(toolResult.getMessageId(), modelToolResult.getMessageId());
    }

    @Test
    void truncatesOversizedToolResultOnlyInCompactionPrompt() {
        AgentSettings settings = compactingSettings();
        FakeCompactionStore store = new FakeCompactionStore();
        CapturingModelProvider provider = new CapturingModelProvider();
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultConversationContextManager manager = new DefaultConversationContextManager(
                settings,
                store,
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper);
        AgentMessage oldUser = turnMessage(
                "m1", "turn-1", AgentMessage.ROLE_USER, "Read the large result");
        AgentMessage toolResult = turnMessage(
                "m2", "turn-1", AgentMessage.ROLE_TOOL, repeated("x", 2500));
        toolResult.setToolName("read");
        toolResult.setToolCallId("call-1");
        AgentMessage currentUser = turnMessage(
                "m3", "turn-2", AgentMessage.ROLE_USER, "Continue");

        manager.prepare(request(Arrays.asList(oldUser, toolResult, currentUser), provider));

        int toolHeader = provider.compactionPrompt.indexOf("[tool]");
        int contentStart = provider.compactionPrompt.indexOf('\n', toolHeader) + 1;
        int contentEnd = provider.compactionPrompt.indexOf("\n\n", contentStart);
        String compactedToolContent = provider.compactionPrompt.substring(contentStart, contentEnd);
        assertEquals(2000, compactedToolContent.length());
        assertTrue(compactedToolContent.endsWith("...[tool result truncated]"));
        assertEquals(2500, toolResult.getContent().length());
    }

    @Test
    void retriesRetryableCompactionModelFailure() {
        AgentSettings settings = compactingSettings();
        settings.setModelRetryMaxAttempts(3);
        settings.setModelRetryInitialDelayMillis(0L);
        settings.setModelRetryMaxDelayMillis(0L);
        FakeCompactionStore store = new FakeCompactionStore();
        RetryOnceCompactionProvider provider = new RetryOnceCompactionProvider();
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultAgentEventPublisher eventPublisher = new DefaultAgentEventPublisher(objectMapper);
        DefaultConversationContextManager manager = new DefaultConversationContextManager(
                settings,
                store,
                eventPublisher,
                objectMapper);
        List<AgentEvent> events = new ArrayList<AgentEvent>();
        List<AgentMessage> messages = Arrays.asList(
                turnMessage("m1", "turn-1", AgentMessage.ROLE_USER, repeated("old context ", 100)),
                turnMessage("m2", "turn-1", AgentMessage.ROLE_ASSISTANT, "old answer"),
                turnMessage("m3", "turn-2", AgentMessage.ROLE_USER, "current request"));

        ConversationContext context = eventPublisher.withEventConsumer(
                events::add,
                () -> manager.prepare(request(messages, provider)));

        assertEquals(2, provider.attempts.get());
        assertEquals(1, store.saveCount);
        assertEquals("summary after retry", store.summary);
        assertEquals(1, context.getMessages().size());
        assertTrue(events.stream().anyMatch(event -> AgentEvent.MODEL_RETRY.equals(event.getType())));
    }

    @Test
    void usesLatestActualUsageAsEstimateBaseline() {
        AgentSettings settings = new AgentSettings();
        settings.setCompactionEnabled(false);
        ObjectMapper objectMapper = new ObjectMapper();
        FakeUsageStore usageStore = new FakeUsageStore();
        usageStore.latest = new ModelCallUsage();
        usageStore.latest.setMessageId("m2");
        usageStore.latest.setActualContextTokens(40);
        DefaultConversationContextManager manager = new DefaultConversationContextManager(
                settings,
                new NoopCompactionStore(),
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper,
                usageStore);

        AgentMessage user = message("m1", AgentMessage.ROLE_USER, "first");
        AgentMessage assistant = message("m2", AgentMessage.ROLE_ASSISTANT, "answer");
        AgentMessage followUp = message("m3", AgentMessage.ROLE_USER, "new follow up");

        ConversationContext context = manager.prepare(request(Arrays.asList(user, assistant, followUp)));

        int deltaTokens = new TokenEstimator().estimateMessages(Collections.singletonList(followUp));
        assertEquals(40 + deltaTokens, context.getEstimatedTokens());
        assertEquals(ModelCallUsage.ESTIMATE_SOURCE_ACTUAL_BASELINE, context.getEstimateSource());
    }

    @Test
    void fallsBackToFullEstimateWhenUsageBaselineMessageIsMissing() {
        AgentSettings settings = new AgentSettings();
        settings.setCompactionEnabled(false);
        ObjectMapper objectMapper = new ObjectMapper();
        FakeUsageStore usageStore = new FakeUsageStore();
        usageStore.latest = new ModelCallUsage();
        usageStore.latest.setMessageId("missing");
        usageStore.latest.setActualContextTokens(40);
        DefaultConversationContextManager manager = new DefaultConversationContextManager(
                settings,
                new NoopCompactionStore(),
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper,
                usageStore);

        AgentMessage user = message("m1", AgentMessage.ROLE_USER, "first");

        ConversationContext context = manager.prepare(request(Collections.singletonList(user)));

        assertEquals(context.getRawEstimatedTokens(), context.getEstimatedTokens());
        assertEquals(ModelCallUsage.ESTIMATE_SOURCE_FULL, context.getEstimateSource());
    }

    @Test
    void invalidatesActualUsageBaselineAfterCompactionChangesContext() {
        AgentSettings settings = new AgentSettings();
        settings.setCompactionEnabled(true);
        settings.setContextWindowTokens(100);
        settings.setCompactionThresholdRatio(0.5d);
        settings.setCompactionRecentMessages(2);
        ObjectMapper objectMapper = new ObjectMapper();
        FakeCompactionStore compactionStore = new FakeCompactionStore();
        FakeUsageStore usageStore = new FakeUsageStore();
        usageStore.latest = new ModelCallUsage();
        usageStore.latest.setMessageId("m4");
        usageStore.latest.setActualContextTokens(100);
        usageStore.latest.setCreatedAt(Instant.EPOCH);
        DefaultConversationContextManager manager = new DefaultConversationContextManager(
                settings,
                compactionStore,
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper,
                usageStore);

        AgentMessage oldUser = message("m1", AgentMessage.ROLE_USER, "old user context");
        AgentMessage oldAssistant = message("m2", AgentMessage.ROLE_ASSISTANT, "old assistant context");
        AgentMessage recentUser = message("m3", AgentMessage.ROLE_USER, "recent user context");
        AgentMessage recentAssistant = message("m4", AgentMessage.ROLE_ASSISTANT, "recent assistant context");

        ConversationContext context = manager.prepare(new ConversationContextRequest(
                "s1",
                "r1",
                "t1",
                "System prompt",
                Arrays.asList(oldUser, oldAssistant, recentUser, recentAssistant),
                Collections.<ToolDefinition>emptyList(),
                new CapturingModelProvider()));

        assertEquals("m2", compactionStore.cursorMessageId);
        assertEquals(ModelCallUsage.ESTIMATE_SOURCE_FULL, context.getEstimateSource());
        assertEquals(context.getRawEstimatedTokens(), context.getEstimatedTokens());
        assertEquals(1, usageStore.findCount);

        ConversationContext nextContext = manager.prepare(new ConversationContextRequest(
                "s1",
                "r1",
                "t2",
                "System prompt",
                Arrays.asList(oldUser, oldAssistant, recentUser, recentAssistant),
                Collections.<ToolDefinition>emptyList(),
                new CapturingModelProvider()));

        assertEquals(ModelCallUsage.ESTIMATE_SOURCE_FULL, nextContext.getEstimateSource());
        assertEquals(nextContext.getRawEstimatedTokens(), nextContext.getEstimatedTokens());
    }

    private DefaultConversationContextManager manager() {
        AgentSettings settings = new AgentSettings();
        settings.setCompactionEnabled(false);
        ObjectMapper objectMapper = new ObjectMapper();
        return new DefaultConversationContextManager(
                settings,
                new NoopCompactionStore(),
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper);
    }

    private ConversationContextRequest request(List<AgentMessage> messages) {
        return request(messages, null);
    }

    private ConversationContextRequest request(List<AgentMessage> messages, ModelProvider provider) {
        return new ConversationContextRequest(
                "s1",
                "r1",
                "t1",
                "System prompt",
                messages,
                Collections.<ToolDefinition>emptyList(),
                provider);
    }

    private static AgentMessage message(String messageId, String role, String content) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(messageId);
        message.setSessionId("s1");
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static AgentMessage turnMessage(String messageId,
                                            String turnId,
                                            String role,
                                            String content) {
        AgentMessage message = message(messageId, role, content);
        message.setTurnId(turnId);
        return message;
    }

    private static AgentSettings compactingSettings() {
        AgentSettings settings = new AgentSettings();
        settings.setModel("test-model");
        settings.setCompactionEnabled(true);
        settings.setContextWindowTokens(1);
        settings.setCompactionThresholdRatio(1.0d);
        settings.setCompactionRecentMessages(1);
        settings.setCompactionTargetTokens(1);
        return settings;
    }

    private static String repeated(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static class FakeCompactionStore implements CompactionStore {
        private String summary;
        private String cursorMessageId;
        private Instant updatedAt;
        private int saveCount;

        @Override
        public CompactionState findBySessionId(String sessionId) {
            if (cursorMessageId == null) {
                return null;
            }
            CompactionState state = new CompactionState();
            state.setSessionId(sessionId);
            state.setSummary(summary);
            state.setCursorMessageId(cursorMessageId);
            state.setUpdatedAt(updatedAt);
            return state;
        }

        @Override
        public void save(String sessionId, String summary, String cursorMessageId) {
            saveCount++;
            this.summary = summary;
            this.cursorMessageId = cursorMessageId;
            this.updatedAt = Instant.now();
        }
    }

    private static class FakeUsageStore implements ModelCallUsageStore {
        private ModelCallUsage latest;
        private int findCount;

        @Override
        public ModelCallUsage findLatestBySessionId(String sessionId) {
            findCount++;
            return latest;
        }

        @Override
        public void append(ModelCallUsage usage) {
        }
    }

    private static class CapturingModelProvider implements ModelProvider {
        private String compactionPrompt;
        private String summary = "Compacted summary";
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public String getName() {
            return "capturing";
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            attempts.incrementAndGet();
            compactionPrompt = request.getMessages().get(0).getContent();
            ModelResponse response = new ModelResponse();
            response.setContent(summary);
            response.setToolCalls(Collections.<ToolCall>emptyList());
            return response;
        }
    }

    private static class RetryOnceCompactionProvider implements ModelProvider {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public String getName() {
            return "retry-once";
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelResponse chat(ModelRequest request,
                                  ModelDeltaConsumer deltaConsumer,
                                  StopSignal stopSignal) {
            if (attempts.incrementAndGet() == 1) {
                throw new ModelProviderException("temporary compaction failure", null, true);
            }
            ModelResponse response = new ModelResponse();
            response.setContent("summary after retry");
            response.setToolCalls(Collections.<ToolCall>emptyList());
            return response;
        }
    }
}

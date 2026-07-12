package io.github.differentialmanifold.jagentharness.core.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.event.DefaultAgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
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
        return new ConversationContextRequest(
                "s1",
                "t1",
                "System prompt",
                messages,
                Collections.<ToolDefinition>emptyList(),
                null);
    }

    private static AgentMessage message(String messageId, String role, String content) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(messageId);
        message.setSessionId("s1");
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static class FakeCompactionStore implements CompactionStore {
        private String summary;
        private String cursorMessageId;
        private Instant updatedAt;

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

        @Override
        public String getName() {
            return "capturing";
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            compactionPrompt = request.getMessages().get(0).getContent();
            ModelResponse response = new ModelResponse();
            response.setContent("Compacted summary");
            response.setToolCalls(Collections.<ToolCall>emptyList());
            return response;
        }
    }
}

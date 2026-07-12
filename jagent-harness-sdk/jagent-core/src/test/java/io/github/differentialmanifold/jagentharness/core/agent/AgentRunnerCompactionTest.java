package io.github.differentialmanifold.jagentharness.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.conversation.CompactionState;
import io.github.differentialmanifold.jagentharness.core.conversation.CompactionStore;
import io.github.differentialmanifold.jagentharness.core.conversation.DefaultConversationContextManager;
import io.github.differentialmanifold.jagentharness.core.conversation.TokenEstimator;
import io.github.differentialmanifold.jagentharness.core.event.DefaultAgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.session.SessionStore;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptContext;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.provider.ModelUsage;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.tool.DefaultToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsage;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsageStore;
import org.junit.jupiter.api.Test;

class AgentRunnerCompactionTest {

    @Test
    void compactsOlderMessagesWhenConversationApproachesContextWindow() {
        FakeSessionStore store = new FakeSessionStore();
        FakeCompactionStore compactionStore = new FakeCompactionStore();
        store.messages.add(message("m1", AgentMessage.ROLE_USER, repeated("old user requirement ", 20)));
        store.messages.add(message("m2", AgentMessage.ROLE_ASSISTANT, repeated("old assistant answer ", 20)));
        store.messages.add(message("m3", AgentMessage.ROLE_USER, repeated("old follow up ", 20)));
        store.messages.add(message("m4", AgentMessage.ROLE_ASSISTANT, "recent assistant answer"));

        FakeModelProvider provider = new FakeModelProvider();
        ModelProviderRegistry providers = new ModelProviderRegistry();
        providers.register(provider);
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultAgentEventPublisher eventPublisher = new DefaultAgentEventPublisher(objectMapper);
        AgentSettings settings = settings();

        AgentRunner runner = new AgentRunner(
                settings,
                store,
                eventPublisher,
                new StaticPromptProvider(),
                new ToolRegistry(),
                providers,
                new DefaultToolContextFactory(),
                new DefaultConversationContextManager(settings, compactionStore, eventPublisher, objectMapper),
                objectMapper);

        List<AgentEvent> events = new ArrayList<AgentEvent>();
        AgentRunResult result = runner.run("s1", "new request that must remain verbatim",
                AgentRunOptions.builder().eventConsumer(events::add).build());

        assertEquals("summary from compact", compactionStore.summary);
        assertEquals("m2", compactionStore.cursorMessageId);
        assertEquals(1, provider.compactionRequests.size());
        assertEquals(1, provider.normalRequests.size());

        ModelRequest normalRequest = provider.normalRequests.get(0);
        assertTrue(normalRequest.getSystemPrompt().contains("Compacted Conversation Summary"));
        assertTrue(normalRequest.getSystemPrompt().contains("summary from compact"));
        assertEquals(3, normalRequest.getMessages().size());
        assertEquals("m3", normalRequest.getMessages().get(0).getMessageId());
        assertEquals("m4", normalRequest.getMessages().get(1).getMessageId());
        assertEquals("new request that must remain verbatim", normalRequest.getMessages().get(2).getContent());
        assertFalse(containsMessage(normalRequest.getMessages(), "m1"));
        assertFalse(containsMessage(normalRequest.getMessages(), "m2"));
        AgentMessage userMessage = store.messages.get(store.messages.size() - 2);
        AgentMessage assistantMessage = store.messages.get(store.messages.size() - 1);
        assertEquals(result.getTurnId(), userMessage.getTurnId());
        assertEquals(result.getTurnId(), assistantMessage.getTurnId());
        assertEquals("m4", userMessage.getParentMessageId());
        assertEquals(userMessage.getMessageId(), assistantMessage.getParentMessageId());

        assertTrue(events.stream().anyMatch(event -> AgentEvent.COMPACTION_START.equals(event.getType())));
        assertTrue(events.stream().anyMatch(event -> AgentEvent.COMPACTION_END.equals(event.getType())));
    }

    @Test
    void savesModelUsageAfterAssistantMessageIsPersisted() {
        FakeSessionStore store = new FakeSessionStore();
        CapturingUsageStore usageStore = new CapturingUsageStore();
        FakeModelProvider provider = new FakeModelProvider();
        provider.usage = new ModelUsage();
        provider.usage.setPromptTokens(24);
        provider.usage.setCompletionTokens(251);
        provider.usage.setReasoningTokens(239);
        provider.usage.setCachedTokens(0);
        provider.usage.setTotalTokens(275);
        ModelProviderRegistry providers = new ModelProviderRegistry();
        providers.register(provider);
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultAgentEventPublisher eventPublisher = new DefaultAgentEventPublisher(objectMapper);
        AgentSettings settings = settings();
        settings.setCompactionEnabled(false);

        AgentRunner runner = new AgentRunner(
                settings,
                store,
                eventPublisher,
                new StaticPromptProvider(),
                new ToolRegistry(),
                providers,
                new DefaultToolContextFactory(),
                new DefaultConversationContextManager(settings, new FakeCompactionStore(), eventPublisher, objectMapper),
                usageStore,
                objectMapper);

        List<AgentEvent> events = new ArrayList<AgentEvent>();
        runner.run("s1", "hello", AgentRunOptions.builder().eventConsumer(events::add).build());

        AgentMessage assistant = store.messages.get(store.messages.size() - 1);
        assertEquals(assistant.getMessageId(), usageStore.appended.getMessageId());
        assertEquals(Integer.valueOf(36), usageStore.appended.getActualContextTokens());
        ModelRequest modelRequest = provider.normalRequests.get(0);
        TokenEstimator estimator = new TokenEstimator();
        int expectedNextContextEstimate = estimator.estimateText(modelRequest.getSystemPrompt())
                + estimator.estimateMessages(modelRequest.getMessages())
                + estimator.estimateMessages(Collections.singletonList(assistant));
        assertEquals(Integer.valueOf(expectedNextContextEstimate), usageStore.appended.getEstimatedTokens());
        assertTrue(events.stream().anyMatch(event -> AgentEvent.CONTEXT_USAGE.equals(event.getType())));
    }

    @Test
    void continuesRunAndPublishesUsageWhenUsagePersistenceFails() {
        FakeSessionStore store = new FakeSessionStore();
        FakeModelProvider provider = new FakeModelProvider();
        provider.usage = new ModelUsage();
        provider.usage.setPromptTokens(10);
        provider.usage.setCompletionTokens(5);
        provider.usage.setTotalTokens(15);
        ModelProviderRegistry providers = new ModelProviderRegistry();
        providers.register(provider);
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultAgentEventPublisher eventPublisher = new DefaultAgentEventPublisher(objectMapper);
        AgentSettings settings = settings();
        settings.setCompactionEnabled(false);

        AgentRunner runner = new AgentRunner(
                settings,
                store,
                eventPublisher,
                new StaticPromptProvider(),
                new ToolRegistry(),
                providers,
                new DefaultToolContextFactory(),
                new DefaultConversationContextManager(settings, new FakeCompactionStore(), eventPublisher, objectMapper),
                new FailingUsageStore(),
                objectMapper);

        List<AgentEvent> events = new ArrayList<AgentEvent>();
        AgentRunResult result = runner.run(
                "s1",
                "hello",
                AgentRunOptions.builder().eventConsumer(events::add).build());

        assertEquals("final answer", result.getAnswer());
        assertEquals(2, store.messages.size());
        assertTrue(events.stream().anyMatch(event -> AgentEvent.CONTEXT_USAGE.equals(event.getType())));
        assertTrue(events.stream().anyMatch(event -> AgentEvent.AGENT_END.equals(event.getType())));
    }

    private static AgentSettings settings() {
        AgentSettings settings = new AgentSettings();
        settings.setProvider("fake");
        settings.setModel("fake-model");
        settings.setCompactionEnabled(true);
        settings.setContextWindowTokens(60);
        settings.setCompactionThresholdRatio(0.5d);
        settings.setCompactionRecentMessages(2);
        settings.setCompactionTargetTokens(100);
        return settings;
    }

    private static AgentMessage message(String messageId, String role, String content) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(messageId);
        message.setSessionId("s1");
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(Instant.now());
        return message;
    }

    private static String repeated(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static boolean containsMessage(List<AgentMessage> messages, String messageId) {
        for (AgentMessage message : messages) {
            if (messageId.equals(message.getMessageId())) {
                return true;
            }
        }
        return false;
    }

    private static class StaticPromptProvider implements PromptProvider {

        @Override
        public String buildSystemPrompt(PromptContext context) {
            return "System prompt.";
        }
    }

    private static class FakeModelProvider implements ModelProvider {

        private final List<ModelRequest> compactionRequests = new ArrayList<ModelRequest>();
        private final List<ModelRequest> normalRequests = new ArrayList<ModelRequest>();
        private ModelUsage usage;

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            ModelResponse response = new ModelResponse();
            response.setToolCalls(Collections.<ToolCall>emptyList());
            if (request.getSystemPrompt() != null
                    && request.getSystemPrompt().contains("compact long agent conversations")) {
                compactionRequests.add(request);
                response.setContent("summary from compact");
                return response;
            }

            normalRequests.add(request);
            response.setContent("final answer");
            response.setUsage(usage);
            return response;
        }
    }

    private static class CapturingUsageStore implements ModelCallUsageStore {

        private ModelCallUsage appended;

        @Override
        public ModelCallUsage findLatestBySessionId(String sessionId) {
            return null;
        }

        @Override
        public void append(ModelCallUsage usage) {
            this.appended = usage;
        }
    }

    private static class FailingUsageStore implements ModelCallUsageStore {

        @Override
        public ModelCallUsage findLatestBySessionId(String sessionId) {
            return null;
        }

        @Override
        public void append(ModelCallUsage usage) {
            throw new IllegalStateException("usage store unavailable");
        }
    }

    private static class FakeSessionStore implements SessionStore {

        private final SessionRecord session;
        private final List<AgentMessage> messages = new ArrayList<AgentMessage>();
        private FakeSessionStore() {
            session = new SessionRecord();
            session.setSessionId("s1");
            session.setTitle("Test session");
            session.setStatus("active");
            session.setCreatedAt(Instant.now());
            session.setUpdatedAt(Instant.now());
        }

        @Override
        public SessionRecord requireSession(String id) {
            return session;
        }

        @Override
        public List<AgentMessage> findMessages(String sessionId) {
            return new ArrayList<AgentMessage>(messages);
        }

        @Override
        public void appendMessage(AgentMessage message) {
            messages.add(message);
        }

        @Override
        public void touch(String sessionId) {
            session.setUpdatedAt(Instant.now());
        }
    }

    private static class FakeCompactionStore implements CompactionStore {

        private String summary;
        private String cursorMessageId;

        @Override
        public CompactionState findBySessionId(String sessionId) {
            if (summary == null && cursorMessageId == null) {
                return null;
            }
            CompactionState state = new CompactionState();
            state.setSessionId(sessionId);
            state.setSummary(summary);
            state.setCursorMessageId(cursorMessageId);
            return state;
        }

        @Override
        public void save(String sessionId, String summary, String cursorMessageId) {
            this.summary = summary;
            this.cursorMessageId = cursorMessageId;
        }
    }
}

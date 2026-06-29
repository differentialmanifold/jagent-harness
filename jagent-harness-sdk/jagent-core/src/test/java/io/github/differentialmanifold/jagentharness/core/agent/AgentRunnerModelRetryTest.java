package io.github.differentialmanifold.jagentharness.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContext;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.event.DefaultAgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelDeltaConsumer;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.session.SessionStore;
import io.github.differentialmanifold.jagentharness.core.tool.DefaultToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

class AgentRunnerModelRetryTest {

    @Test
    void retriesRetryableModelFailureBeforeOutputAndPublishesRetryEvent() throws Exception {
        FakeSessionStore store = new FakeSessionStore();
        RetryOnceModelProvider provider = new RetryOnceModelProvider();
        ModelProviderRegistry providers = new ModelProviderRegistry();
        providers.register(provider);
        ObjectMapper objectMapper = new ObjectMapper();
        List<AgentEvent> events = new ArrayList<AgentEvent>();

        AgentRunResult result = createRunner(store, providers, objectMapper)
                .run("s1", "hello", AgentRunOptions.builder().eventConsumer(events::add).build());

        assertEquals("recovered", result.getAnswer());
        assertEquals(2, provider.attempts.get());
        assertEquals(2, store.messages.size());
        assertEquals("recovered", store.messages.get(1).getContent());

        AgentEvent retryEvent = events.stream()
                .filter(event -> AgentEvent.MODEL_RETRY.equals(event.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("model_retry event was not published"));
        assertEquals(1, objectMapper.readTree(retryEvent.getPayloadJson()).path("attempt").asInt());
        assertEquals(2, objectMapper.readTree(retryEvent.getPayloadJson()).path("nextAttempt").asInt());
        assertTrue(objectMapper.readTree(retryEvent.getPayloadJson()).path("error").asText().contains("temporary outage"));
    }

    @Test
    void retriesAfterModelOutputStartedAndResetsAttemptState() throws Exception {
        FakeSessionStore store = new FakeSessionStore();
        RetryAfterDeltaModelProvider provider = new RetryAfterDeltaModelProvider();
        ModelProviderRegistry providers = new ModelProviderRegistry();
        providers.register(provider);
        ObjectMapper objectMapper = new ObjectMapper();
        List<AgentEvent> events = new ArrayList<AgentEvent>();

        AgentRunResult result = createRunner(store, providers, objectMapper)
                .run("s1", "hello", AgentRunOptions.builder().eventConsumer(events::add).build());

        assertEquals("recovered", result.getAnswer());
        assertEquals(2, provider.attempts.get());
        assertEquals(2, store.messages.size());
        assertEquals("recovered", store.messages.get(1).getContent());
        assertEquals("new reasoning", store.messages.get(1).getReasoningContent());

        AgentEvent retryEvent = events.stream()
                .filter(event -> AgentEvent.MODEL_RETRY.equals(event.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("model_retry event was not published"));
        assertTrue(objectMapper.readTree(retryEvent.getPayloadJson()).path("resetOutput").asBoolean());

        List<AgentEvent> contentUpdates = events.stream()
                .filter(event -> AgentEvent.MESSAGE_UPDATE.equals(event.getType()))
                .collect(Collectors.toList());
        assertEquals(2, contentUpdates.size());
        assertEquals("partial", objectMapper.readTree(contentUpdates.get(0).getPayloadJson()).path("delta").asText());
        assertEquals(0, objectMapper.readTree(contentUpdates.get(0).getPayloadJson()).path("index").asInt());
        assertEquals("recovered", objectMapper.readTree(contentUpdates.get(1).getPayloadJson()).path("delta").asText());
        assertEquals(0, objectMapper.readTree(contentUpdates.get(1).getPayloadJson()).path("index").asInt());

        List<AgentEvent> reasoningUpdates = events.stream()
                .filter(event -> AgentEvent.MESSAGE_REASONING_UPDATE.equals(event.getType()))
                .collect(Collectors.toList());
        assertEquals(2, reasoningUpdates.size());
        assertEquals(0, objectMapper.readTree(reasoningUpdates.get(0).getPayloadJson()).path("index").asInt());
        assertEquals(0, objectMapper.readTree(reasoningUpdates.get(1).getPayloadJson()).path("index").asInt());
    }

    private AgentRunner createRunner(FakeSessionStore store,
                                     ModelProviderRegistry providers,
                                     ObjectMapper objectMapper) {
        return new AgentRunner(
                settings(),
                store,
                new DefaultAgentEventPublisher(objectMapper),
                context -> "System prompt.",
                new ToolRegistry(),
                providers,
                new DefaultToolContextFactory(),
                request -> new ConversationContext(request.getSystemPrompt(), request.getMessages()),
                objectMapper);
    }

    private AgentSettings settings() {
        AgentSettings settings = new AgentSettings();
        settings.setProvider("retrying");
        settings.setModel("test-model");
        settings.setModelRetryMaxAttempts(3);
        settings.setModelRetryInitialDelayMillis(0L);
        settings.setModelRetryMaxDelayMillis(0L);
        return settings;
    }

    private static class RetryOnceModelProvider implements ModelProvider {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public String getName() {
            return "retrying";
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelResponse chat(ModelRequest request,
                                  ModelDeltaConsumer deltaConsumer,
                                  StopSignal stopSignal) {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new ModelProviderException("temporary outage", null, true);
            }
            deltaConsumer.onContentDelta("recovered");
            ModelResponse response = new ModelResponse();
            response.setContent("recovered");
            response.setToolCalls(Collections.<ToolCall>emptyList());
            return response;
        }
    }

    private static class RetryAfterDeltaModelProvider implements ModelProvider {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public String getName() {
            return "retrying";
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelResponse chat(ModelRequest request,
                                  ModelDeltaConsumer deltaConsumer,
                                  StopSignal stopSignal) {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                deltaConsumer.onReasoningDelta("old reasoning");
                deltaConsumer.onContentDelta("partial");
                throw new ModelProviderException("stream failed", null, true);
            }
            deltaConsumer.onReasoningDelta("new reasoning");
            deltaConsumer.onContentDelta("recovered");
            ModelResponse response = new ModelResponse();
            response.setReasoningContent("new reasoning");
            response.setContent("recovered");
            response.setToolCalls(Collections.<ToolCall>emptyList());
            return response;
        }
    }

    private static class FakeSessionStore implements SessionStore {
        private final SessionRecord session;
        private final List<AgentMessage> messages = new ArrayList<AgentMessage>();

        private FakeSessionStore() {
            session = new SessionRecord();
            session.setSessionId("s1");
            session.setTitle("Test");
            session.setStatus("active");
            session.setCreatedAt(Instant.now());
            session.setUpdatedAt(Instant.now());
        }

        @Override
        public SessionRecord requireSession(String sessionId) {
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
}

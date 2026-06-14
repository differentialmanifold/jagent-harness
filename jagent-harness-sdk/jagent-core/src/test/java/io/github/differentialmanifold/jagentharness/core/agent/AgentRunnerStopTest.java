package io.github.differentialmanifold.jagentharness.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContext;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.event.DefaultAgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.session.SessionStore;
import io.github.differentialmanifold.jagentharness.core.tool.DefaultToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

class AgentRunnerStopTest {

    @Test
    void persistsPartialAssistantMessageAndPublishesStoppedEvent() {
        RunControl control = new RunControl();
        FakeSessionStore store = new FakeSessionStore();
        ModelProviderRegistry providers = new ModelProviderRegistry();
        providers.register(new StoppingModelProvider(control));
        ObjectMapper objectMapper = new ObjectMapper();
        List<AgentEvent> events = new ArrayList<AgentEvent>();

        AgentRunner runner = createRunner(store, providers, objectMapper);

        assertThrows(
                StopRequestedException.class,
                () -> runner.run(
                        "s1",
                        "stop this",
                        AgentRunOptions.builder()
                                .eventConsumer(events::add)
                                .stopSignal(control)
                                .build()));

        assertEquals(2, store.messages.size());
        assertEquals(AgentMessage.ROLE_USER, store.messages.get(0).getRole());
        assertEquals(AgentMessage.ROLE_ASSISTANT, store.messages.get(1).getRole());
        assertEquals("partial answer", store.messages.get(1).getContent());
        assertTrue(events.stream().anyMatch(event -> AgentEvent.AGENT_STOPPED.equals(event.getType())));
    }

    @Test
    void doesNotPersistSyntheticAssistantMessageWhenStoppedBeforeOutput() {
        RunControl control = new RunControl();
        control.requestStop();
        FakeSessionStore store = new FakeSessionStore();
        ModelProviderRegistry providers = new ModelProviderRegistry();
        providers.register(new StoppingModelProvider(control));
        ObjectMapper objectMapper = new ObjectMapper();
        List<AgentEvent> events = new ArrayList<AgentEvent>();

        AgentRunner runner = createRunner(store, providers, objectMapper);

        assertThrows(
                StopRequestedException.class,
                () -> runner.run(
                        "s1",
                        "stop this",
                        AgentRunOptions.builder()
                                .eventConsumer(events::add)
                                .stopSignal(control)
                                .build()));

        assertEquals(1, store.messages.size());
        assertEquals(AgentMessage.ROLE_USER, store.messages.get(0).getRole());
        assertTrue(events.stream().anyMatch(event -> AgentEvent.AGENT_STOPPED.equals(event.getType())));
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
        settings.setProvider("stopping");
        settings.setModel("test-model");
        return settings;
    }

    private static class StoppingModelProvider implements ModelProvider {
        private final RunControl control;

        private StoppingModelProvider(RunControl control) {
            this.control = control;
        }

        @Override
        public String getName() {
            return "stopping";
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelResponse chat(ModelRequest request, Consumer<String> contentDeltaConsumer) {
            contentDeltaConsumer.accept("partial answer");
            control.requestStop();
            ModelResponse response = new ModelResponse();
            response.setContent("partial answer");
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

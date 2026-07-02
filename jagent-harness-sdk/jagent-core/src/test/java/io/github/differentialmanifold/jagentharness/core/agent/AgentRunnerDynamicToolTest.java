package io.github.differentialmanifold.jagentharness.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContext;
import io.github.differentialmanifold.jagentharness.core.event.DefaultAgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelDeltaConsumer;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.session.SessionStore;
import io.github.differentialmanifold.jagentharness.core.tool.DefaultToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

class AgentRunnerDynamicToolTest {

    @Test
    void executesToolProvidedForCurrentAgentContext() {
        FakeSessionStore store = new FakeSessionStore();
        AtomicBoolean executed = new AtomicBoolean();
        ToolDefinition dynamicTool = new ToolDefinition() {
            @Override
            public String getName() {
                return "remote__ping";
            }

            @Override
            public String getDescription() {
                return "Returns pong.";
            }

            @Override
            public JsonNode getParametersSchema() {
                return null;
            }

            @Override
            public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
                assertEquals("s1", context.getSessionId());
                assertEquals("remote__ping", context.getCurrentToolName());
                executed.set(true);
                return ToolExecutionResult.of("pong");
            }
        };
        ToolRegistry tools = new ToolRegistry(
                Collections.<ToolDefinition>emptyList(),
                Collections.singletonList(context -> context != null
                        ? Collections.singletonList(dynamicTool)
                        : Collections.<ToolDefinition>emptyList()));
        DynamicToolModelProvider provider = new DynamicToolModelProvider();
        ModelProviderRegistry providers = new ModelProviderRegistry();
        providers.register(provider);
        ObjectMapper objectMapper = new ObjectMapper();

        AgentRunResult result = new AgentRunner(
                settings(),
                store,
                new DefaultAgentEventPublisher(objectMapper),
                context -> "System prompt.",
                tools,
                providers,
                new DefaultToolContextFactory(),
                request -> new ConversationContext(request.getSystemPrompt(), request.getMessages()),
                objectMapper)
                .run("s1", "ping");

        assertEquals("done", result.getAnswer());
        assertTrue(executed.get());
        assertEquals(2, provider.calls.get());
        assertTrue(store.messages.stream()
                .anyMatch(message -> "remote__ping".equals(message.getToolName())
                        && "pong".equals(message.getContent())));
    }

    private AgentSettings settings() {
        AgentSettings settings = new AgentSettings();
        settings.setProvider("dynamic-tool-test");
        settings.setModel("test-model");
        return settings;
    }

    private static class DynamicToolModelProvider implements ModelProvider {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getName() {
            return "dynamic-tool-test";
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelResponse chat(ModelRequest request,
                                  ModelDeltaConsumer deltaConsumer,
                                  StopSignal stopSignal) {
            assertTrue(request.getTools().stream()
                    .anyMatch(tool -> "remote__ping".equals(tool.getName())));
            ModelResponse response = new ModelResponse();
            if (calls.incrementAndGet() == 1) {
                response.setToolCalls(Collections.singletonList(
                        new ToolCall("call-1", "remote__ping", "{}")));
            } else {
                response.setContent("done");
                response.setToolCalls(Collections.<ToolCall>emptyList());
            }
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

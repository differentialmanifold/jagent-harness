package io.github.differentialmanifold.jagentharness.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContext;
import io.github.differentialmanifold.jagentharness.core.event.*;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.provider.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import java.util.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class AgentRunnerProgressTest {
    @Test
    void remindsThenStopsRepeatedServerFailuresAndAllowsANewUserTurn() {
        Fixture f = new Fixture();
        NoProgressException error = assertThrows(NoProgressException.class, () -> f.run());
        assertTrue(error.getMessage().contains("same tools"));
        assertEquals(4, f.modelCalls);
        assertTrue(f.reminderSeen);
        assertEquals(1, f.messages.stream().filter(AgentMessage::isRuntimeContext).count());
        assertEquals("failed", f.messages.get(f.messages.size() - 1).getStopReason());
        assertTrue(
                f.events.stream()
                        .anyMatch(
                                e ->
                                        AgentEvent.AGENT_ERROR.equals(e.getType())
                                                && e.getPayloadJson().contains("NO_PROGRESS")));
        assertTrue(
                f.events.stream()
                        .anyMatch(
                                e ->
                                        AgentEvent.TURN_END.equals(e.getType())
                                                && e.getPayloadJson().contains("NO_PROGRESS")));
        f.finishAfter = f.modelCalls;
        assertEquals("done", f.run().getAnswer());
    }

    @Test
    void letsTheModelRecoverAfterTheReminder() {
        Fixture f = new Fixture();
        f.finishAfter = 3;
        assertEquals("done", f.run().getAnswer());
        assertTrue(f.reminderSeen);
        assertFalse(f.events.stream().anyMatch(e -> AgentEvent.AGENT_ERROR.equals(e.getType())));
    }

    @Test
    void successfulPollingAndChangingFailuresHaveNoTurnCap() {
        for (int scenario = 0; scenario < 4; scenario++) {
            Fixture f = new Fixture();
            f.finishAfter = 80;
            if (scenario == 0) f.outcome = n -> ToolExecutionResult.of("still running");
            if (scenario == 1) f.arguments = n -> "{\"file\":\"file-" + n + "\"}";
            if (scenario == 2)
                f.outcome = n -> ToolExecutionResult.failure("404 missing file-" + n);
            if (scenario == 3) f.outcome = n -> ToolExecutionResult.unknown("delivery interrupted");
            assertEquals("done", f.run().getAnswer());
            assertEquals(81, f.modelCalls);
            assertFalse(f.reminderSeen);
        }
    }

    private static class Fixture implements SessionStore {
        final List<AgentMessage> messages = new ArrayList<>();
        final List<AgentEvent> events = new ArrayList<>();
        int modelCalls;
        int finishAfter = 20;
        boolean reminderSeen;
        IntFunction<String> arguments = n -> "{}";
        IntFunction<ToolExecutionResult> outcome =
                n -> ToolExecutionResult.error("Permission denied");

        AgentRunResult run() {
            ObjectMapper json = new ObjectMapper();
            AgentSettings settings = new AgentSettings();
            settings.setProvider("loop-test");
            settings.setModel("test");
            ModelProviderRegistry providers = new ModelProviderRegistry();
            providers.register(
                    new ModelProvider() {
                        public String getName() {
                            return "loop-test";
                        }

                        public ModelResponse chat(ModelRequest request) {
                            modelCalls++;
                            assertTrue(modelCalls <= 100, "Test fuse: failed to stop or finish");
                            reminderSeen |=
                                    request.getMessages().stream()
                                            .anyMatch(
                                                    m ->
                                                            RepeatedToolFailureDetector.REMINDER
                                                                    .equals(m.getContent()));
                            ModelResponse response = new ModelResponse();
                            if (modelCalls > finishAfter) response.setContent("done");
                            else
                                response.setToolCalls(
                                        Collections.singletonList(
                                                new ToolCall(
                                                        "call-" + modelCalls,
                                                        "test",
                                                        arguments.apply(modelCalls))));
                            return response;
                        }
                    });
            ToolDefinition tool =
                    new ToolDefinition() {
                        public String getName() {
                            return "test";
                        }

                        public String getDescription() {
                            return "Test tool";
                        }

                        public JsonNode getParametersSchema() {
                            return json.createObjectNode().put("type", "object");
                        }

                        public ToolExecutionResult execute(ToolContext context, JsonNode args) {
                            return outcome.apply(modelCalls);
                        }
                    };
            return new AgentRunner(
                            settings,
                            this,
                            new DefaultAgentEventPublisher(json),
                            context -> "Test",
                            new ToolRegistry(Collections.singletonList(tool)),
                            providers,
                            new DefaultToolContextFactory(),
                            request ->
                                    new ConversationContext(
                                            request.getSystemPrompt(), request.getMessages()),
                            json)
                    .run(
                            "session",
                            "Continue with the task",
                            AgentRunOptions.builder().eventConsumer(events::add).build());
        }

        public SessionRecord requireSession(String id) {
            SessionRecord session = new SessionRecord();
            session.setSessionId(id);
            return session;
        }

        public List<AgentMessage> findMessages(String id) {
            throw new AssertionError("Loop must not load full history");
        }

        public List<AgentMessage> findExecutionMessages(String id) {
            return ConversationHistory.executionTail(messages);
        }

        public List<AgentMessage> findRecentMessages(String id, int limit) {
            return ConversationHistory.recent(messages, limit);
        }

        public List<AgentMessage> findContextMessages(String id) {
            return FailedTurn.context(messages);
        }

        public void appendMessage(AgentMessage message) {
            messages.add(message);
        }

        public void touch(String id) {}
    }
}

package io.github.differentialmanifold.jagentharness.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContext;
import io.github.differentialmanifold.jagentharness.core.event.DefaultAgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
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
                assertEquals("run-1", context.getRunId());
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
        List<AgentEvent> events = new ArrayList<AgentEvent>();
        RecordingRunInputSource runInputSource = new RecordingRunInputSource(
                Collections.<List<RunInput>>emptyList());

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
                .run("s1", "ping", AgentRunOptions.builder()
                        .runId("run-1")
                        .eventConsumer(events::add)
                        .runInputSource(runInputSource)
                        .build());

        assertEquals("done", result.getAnswer());
        assertEquals("run-1", result.getRunId());
        assertEquals(2, result.getTurnCount());
        assertNotEquals(result.getFirstTurnId(), result.getLastTurnId());
        assertTrue(executed.get());
        assertEquals(2, provider.calls.get());
        assertTrue(store.messages.stream()
                .anyMatch(message -> "remote__ping".equals(message.getToolName())
                        && "pong".equals(message.getContent())));

        assertEquals(4, store.messages.size());
        for (AgentMessage message : store.messages) {
            assertEquals("run-1", message.getRunId());
        }
        assertEquals(result.getFirstTurnId(), store.messages.get(0).getTurnId());
        assertEquals(result.getFirstTurnId(), store.messages.get(1).getTurnId());
        assertEquals(result.getFirstTurnId(), store.messages.get(2).getTurnId());
        assertEquals(result.getLastTurnId(), store.messages.get(3).getTurnId());

        List<AgentEvent> turnStarts = eventsOfType(events, AgentEvent.TURN_START);
        List<AgentEvent> turnEnds = eventsOfType(events, AgentEvent.TURN_END);
        assertEquals(2, turnStarts.size());
        assertEquals(2, turnEnds.size());
        assertEquals(turnStarts.get(0).getTurnId(), turnEnds.get(0).getTurnId());
        assertEquals(turnStarts.get(1).getTurnId(), turnEnds.get(1).getTurnId());
        assertEquals("run-1", turnStarts.get(0).getRunId());
        assertNull(eventsOfType(events, AgentEvent.AGENT_START).get(0).getTurnId());
        assertNull(eventsOfType(events, AgentEvent.AGENT_END).get(0).getTurnId());
        assertEquals(Arrays.asList(
                        result.getFirstTurnId(),
                        result.getLastTurnId()),
                runInputSource.completedTurnIds);
    }

    @Test
    void appliesEachPendingInputBatchAsSeparateMessages() {
        FakeSessionStore store = new FakeSessionStore();
        RuntimeInputModelProvider provider = new RuntimeInputModelProvider();
        ModelProviderRegistry providers = new ModelProviderRegistry();
        providers.register(provider);
        ObjectMapper objectMapper = new ObjectMapper();
        List<AgentEvent> events = new ArrayList<AgentEvent>();
        RecordingRunInputSource runInputSource = new RecordingRunInputSource(Arrays.asList(
                Arrays.asList(
                        new RunInput("input-1", "s1", "run-inputs",
                                "Do not change tests", RunInputStatus.CLAIMED),
                        new RunInput("input-2", "s1", "run-inputs",
                                "Keep public APIs small", RunInputStatus.CLAIMED)),
                Arrays.asList(
                        new RunInput("input-3", "s1", "run-inputs",
                                "Also update the README", RunInputStatus.CLAIMED),
                        new RunInput("input-4", "s1", "run-inputs",
                                "Run the focused tests", RunInputStatus.CLAIMED))));

        AgentSettings settings = new AgentSettings();
        settings.setProvider("runtime-input-test");
        settings.setModel("test-model");
        AgentRunResult result = new AgentRunner(
                settings,
                store,
                new DefaultAgentEventPublisher(objectMapper),
                context -> "System prompt.",
                new ToolRegistry(),
                providers,
                new DefaultToolContextFactory(),
                request -> new ConversationContext(request.getSystemPrompt(), request.getMessages()),
                objectMapper)
                .run("s1", "start", AgentRunOptions.builder()
                        .runId("run-inputs")
                        .runInputSource(runInputSource)
                        .eventConsumer(events::add)
                        .build());

        assertEquals("completed answer", result.getAnswer());
        assertEquals(3, result.getTurnCount());
        assertEquals(3, provider.calls);
        assertEquals(8, store.messages.size());
        assertUserMessage(store.messages.get(2), "Do not change tests", "run-inputs");
        assertUserMessage(store.messages.get(3), "Keep public APIs small", "run-inputs");
        assertEquals(store.messages.get(2).getTurnId(), store.messages.get(3).getTurnId());
        assertUserMessage(store.messages.get(5), "Also update the README", "run-inputs");
        assertUserMessage(store.messages.get(6), "Run the focused tests", "run-inputs");
        assertEquals(store.messages.get(5).getTurnId(), store.messages.get(6).getTurnId());
        assertNotEquals(store.messages.get(2).getTurnId(), store.messages.get(5).getTurnId());
        assertEquals(3, runInputSource.completedTurnIds.size());

        List<AgentEvent> appliedEvents = eventsOfType(events, AgentEvent.RUN_INPUT_BATCH_APPLIED);
        assertEquals(2, appliedEvents.size());
        assertEquals(result.getFirstTurnId(), appliedEvents.get(0).getTurnId());
        assertTrue(appliedEvents.get(0).getPayloadJson().contains("input-1"));
        assertTrue(appliedEvents.get(0).getPayloadJson().contains("input-2"));
        assertTrue(appliedEvents.get(1).getPayloadJson().contains("input-3"));
        assertTrue(appliedEvents.get(1).getPayloadJson().contains("input-4"));
        assertFalse(appliedEvents.get(0).getPayloadJson().contains("\"type\""));
        assertFalse(appliedEvents.get(1).getPayloadJson().contains("\"type\""));
        assertTrue(appliedEvents.get(0).getPayloadJson().contains("claimedAfterTurnId"));
        List<AgentEvent> turnStarts = eventsOfType(events, AgentEvent.TURN_START);
        List<AgentEvent> turnEnds = eventsOfType(events, AgentEvent.TURN_END);
        assertEquals(3, turnStarts.size());
        assertEquals(3, turnEnds.size());
        assertBatchBetweenTurns(events, appliedEvents.get(0), turnEnds.get(0), turnStarts.get(1));
        assertBatchBetweenTurns(events, appliedEvents.get(1), turnEnds.get(1), turnStarts.get(2));
    }

    @Test
    void preservesImagesOnInitialAndRunningUserMessages() {
        FakeSessionStore store = new FakeSessionStore();
        RuntimeInputModelProvider provider = new RuntimeInputModelProvider();
        ModelProviderRegistry providers = new ModelProviderRegistry();
        providers.register(provider);
        MessageImage firstImage = new MessageImage(
                " first.png ",
                " IMAGE/PNG ",
                " data:image/png;base64,Zmlyc3Q= ",
                " HIGH ");
        MessageImage secondImage = new MessageImage(
                " second.jpg ",
                " IMAGE/JPEG ",
                " HTTPS://example.com/second.jpg ",
                " LOW ");
        RecordingRunInputSource runInputSource = new RecordingRunInputSource(Arrays.asList(
                Collections.singletonList(new RunInput(
                        "input-image",
                        "s1",
                        "run-images",
                        "",
                        Collections.singletonList(secondImage),
                        RunInputStatus.CLAIMED))));
        AgentSettings settings = new AgentSettings();
        settings.setProvider("runtime-input-test");
        settings.setModel("test-model");

        AgentRunResult result = new AgentRunner(
                settings,
                store,
                new DefaultAgentEventPublisher(new ObjectMapper()),
                context -> "System prompt.",
                new ToolRegistry(),
                providers,
                new DefaultToolContextFactory(),
                request -> new ConversationContext(request.getSystemPrompt(), request.getMessages()),
                new ObjectMapper())
                .run("s1", "describe", Collections.singletonList(firstImage), AgentRunOptions.builder()
                        .runId("run-images")
                        .runInputSource(runInputSource)
                        .build());

        assertEquals(2, result.getTurnCount());
        MessageImage storedFirstImage = store.messages.get(0).getImages().get(0);
        MessageImage storedSecondImage = store.messages.get(2).getImages().get(0);
        assertNotSame(firstImage, storedFirstImage);
        assertNotSame(secondImage, storedSecondImage);
        assertEquals("first.png", storedFirstImage.getName());
        assertEquals("image/png", storedFirstImage.getMediaType());
        assertEquals("data:image/png;base64,Zmlyc3Q=", storedFirstImage.getUrl());
        assertEquals("high", storedFirstImage.getDetail());
        assertEquals("", store.messages.get(2).getContent());
        assertEquals("second.jpg", storedSecondImage.getName());
        assertEquals("image/jpeg", storedSecondImage.getMediaType());
        assertEquals("HTTPS://example.com/second.jpg", storedSecondImage.getUrl());
        assertEquals("low", storedSecondImage.getDetail());
        firstImage.setName("changed.png");
        secondImage.setName("changed.jpg");
        assertEquals("first.png", storedFirstImage.getName());
        assertEquals("second.jpg", storedSecondImage.getName());
        assertEquals(1, provider.requests.get(0).getMessages().get(0).getImages().size());
        assertEquals(1, provider.requests.get(1).getMessages().get(2).getImages().size());
    }

    @Test
    void rejectsInvalidInitialImagesBeforePersistingTheUserMessage() {
        List<MessageImage> invalidImages = Arrays.asList(
                null,
                new MessageImage("blank.png", "image/png", " "),
                new MessageImage("remote.png", "image/png", "ftp://example.com/image.png"),
                new MessageImage(
                        "detail.png",
                        "image/png",
                        "data:image/png;base64,aGVsbG8=",
                        "original"),
                new MessageImage("media.png", "text/plain", "https://example.com/image.png"),
                new MessageImage("bad\nname.png", "image/png", "https://example.com/image.png"),
                new MessageImage(
                        "mismatch.png",
                        "image/jpeg",
                        "data:image/png;base64,aGVsbG8="),
                new MessageImage(
                        "malformed.png",
                        "image/png",
                        "data:image/png;base64,not-base64"));

        for (MessageImage invalidImage : invalidImages) {
            FakeSessionStore store = new FakeSessionStore();
            RuntimeInputModelProvider provider = new RuntimeInputModelProvider();
            ModelProviderRegistry providers = new ModelProviderRegistry();
            providers.register(provider);
            AgentSettings settings = new AgentSettings();
            settings.setProvider("runtime-input-test");
            settings.setModel("test-model");
            AgentRunner runner = new AgentRunner(
                    settings,
                    store,
                    new DefaultAgentEventPublisher(new ObjectMapper()),
                    context -> "System prompt.",
                    new ToolRegistry(),
                    providers,
                    new DefaultToolContextFactory(),
                    request -> new ConversationContext(request.getSystemPrompt(), request.getMessages()),
                    new ObjectMapper());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> runner.run(
                            "s1",
                            "describe",
                            Collections.singletonList(invalidImage),
                            AgentRunOptions.empty()));
            assertTrue(store.messages.isEmpty());
            assertEquals(0, provider.calls);
        }
    }

    @Test
    void rejectsInvalidRunningImagesWithoutPersistingThemAsMessages() {
        FakeSessionStore store = new FakeSessionStore();
        RuntimeInputModelProvider provider = new RuntimeInputModelProvider();
        ModelProviderRegistry providers = new ModelProviderRegistry();
        providers.register(provider);
        MessageImage invalidImage = new MessageImage(
                "remote.png", "image/png", "ftp://example.com/image.png");
        RecordingRunInputSource runInputSource = new RecordingRunInputSource(Collections.singletonList(
                Collections.singletonList(new RunInput(
                        "invalid-image",
                        "s1",
                        "run-invalid-image",
                        "",
                        Collections.singletonList(invalidImage),
                        RunInputStatus.CLAIMED))));
        AgentSettings settings = new AgentSettings();
        settings.setProvider("runtime-input-test");
        settings.setModel("test-model");
        AgentRunner runner = new AgentRunner(
                settings,
                store,
                new DefaultAgentEventPublisher(new ObjectMapper()),
                context -> "System prompt.",
                new ToolRegistry(),
                providers,
                new DefaultToolContextFactory(),
                request -> new ConversationContext(request.getSystemPrompt(), request.getMessages()),
                new ObjectMapper());

        assertThrows(
                IllegalArgumentException.class,
                () -> runner.run("s1", "start", AgentRunOptions.builder()
                        .runId("run-invalid-image")
                        .runInputSource(runInputSource)
                        .build()));

        assertEquals(2, store.messages.size());
        assertTrue(store.messages.get(0).getImages().isEmpty());
        assertTrue(store.messages.get(1).getImages().isEmpty());
        assertEquals(1, provider.calls);
    }

    private AgentSettings settings() {
        AgentSettings settings = new AgentSettings();
        settings.setProvider("dynamic-tool-test");
        settings.setModel("test-model");
        return settings;
    }

    private static List<AgentEvent> eventsOfType(List<AgentEvent> events, String type) {
        List<AgentEvent> matches = new ArrayList<AgentEvent>();
        for (AgentEvent event : events) {
            if (type.equals(event.getType())) {
                matches.add(event);
            }
        }
        return matches;
    }

    private static void assertUserMessage(AgentMessage message, String content, String runId) {
        assertEquals(AgentMessage.ROLE_USER, message.getRole());
        assertEquals(content, message.getContent());
        assertEquals(runId, message.getRunId());
    }

    private static void assertBatchBetweenTurns(List<AgentEvent> events,
                                                AgentEvent batch,
                                                AgentEvent completedTurn,
                                                AgentEvent nextTurn) {
        assertTrue(events.indexOf(completedTurn) < events.indexOf(batch));
        assertTrue(events.indexOf(batch) < events.indexOf(nextTurn));
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

    private static class RuntimeInputModelProvider implements ModelProvider {
        private int calls;
        private final List<ModelRequest> requests = new ArrayList<ModelRequest>();

        @Override
        public String getName() {
            return "runtime-input-test";
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            calls++;
            requests.add(request);
            ModelResponse response = new ModelResponse();
            response.setContent(calls == 1
                    ? "first answer"
                    : calls == 2 ? "revised answer" : "completed answer");
            response.setToolCalls(Collections.<ToolCall>emptyList());
            return response;
        }
    }

    private static class RecordingRunInputSource implements RunInputSource {
        private final List<List<RunInput>> batches;
        private final List<String> completedTurnIds = new ArrayList<String>();
        private int batchIndex;

        private RecordingRunInputSource(List<List<RunInput>> batches) {
            this.batches = batches;
        }

        @Override
        public List<RunInput> claimPendingInputs(String sessionId,
                                                String runId,
                                                String completedTurnId) {
            completedTurnIds.add(completedTurnId);
            if (batchIndex < batches.size()) {
                return batches.get(batchIndex++);
            }
            return Collections.emptyList();
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

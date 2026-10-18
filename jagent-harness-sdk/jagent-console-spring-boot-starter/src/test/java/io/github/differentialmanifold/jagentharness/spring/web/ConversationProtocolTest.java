package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.provider.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:sqlite:file:conversation-tests?mode=memory&cache=shared",
            "spring.datasource.driver-class-name=org.sqlite.JDBC",
            "harness.model.provider=scripted",
            "harness.model.model=test",
            "harness.compaction.enabled=false",
            "harness.protocol.token=test-token",
            "harness.store.jdbc.application-id=protocol-tests"
        })
@Import(ConversationProtocolTest.Fixtures.class)
class ConversationProtocolTest {
    @Autowired ChatService service;
    @Autowired SessionManager manager;
    @Autowired SessionStore sessions;
    @Autowired Scripted model;
    @Autowired RunInputCoordinator inputs;
    @Autowired JdbcTemplate db;
    @LocalServerPort int port;

    ChatRequest user(String text) {
        ChatRequest request = new ChatRequest();
        request.sessionId = manager.createSession("test", null).getSessionId();
        request.messages.add(ChatMessage.user(text));
        for (String name : Arrays.asList("client_a", "client_b")) {
            ToolDescriptor tool = new ToolDescriptor();
            tool.name = name;
            tool.parameters.put("type", "object");
            request.clientTools.add(tool);
        }
        return request;
    }

    ChatRequest results(ChatResponse response) {
        ChatRequest request = new ChatRequest();
        request.sessionId = response.sessionId;
        for (ToolInvocation call : response.toolCalls)
            request.messages.add(
                    ChatMessage.tool(call.toolCallId, "SUCCEEDED", call.name + " result"));
        return request;
    }

    @Test
    void serverOnlyUsesOriginalLoop() {
        ChatRequest request = user("server-only");
        request.clientTools.clear();
        ChatResponse completed = service.chat(request, null);
        assertEquals("COMPLETED", completed.status);
        assertEquals(completed.answer, service.state(request.sessionId).answer);
        assertEquals("COMPLETED", service.state(request.sessionId).status);
        assertEquals(
                1,
                sessions.findMessages(request.sessionId).stream()
                        .filter(m -> "tool".equals(m.getRole()))
                        .count());
    }

    @Test
    void clientResultsAppendThroughSameChatWithOnlySessionId() {
        ChatRequest request = user("mixed");
        ChatResponse pending = service.chat(request, null);
        assertEquals("REQUIRES_ACTION", pending.status);
        assertEquals(2, pending.toolCalls.size());
        assertEquals(
                1,
                sessions.findMessages(request.sessionId).stream()
                        .filter(m -> "tool".equals(m.getRole()))
                        .count());
        ChatResponse complete = service.chat(results(pending), null);
        assertEquals("COMPLETED", complete.status);
        assertEquals(pending.runId, complete.runId);
        assertEquals(complete.answer, service.state(request.sessionId).answer);
        assertEquals(
                3,
                sessions.findMessages(request.sessionId).stream()
                        .filter(m -> "tool".equals(m.getRole()))
                        .count());
        assertEquals(
                1,
                sessions.findMessages(request.sessionId).stream()
                        .filter(m -> "user".equals(m.getRole()))
                        .count());
    }

    @Test
    void partialResultsAreAppendedWithoutCallingModelUntilComplete() {
        ChatResponse pending = service.chat(user("mixed"), null);
        int calls = model.calls.get();
        ChatRequest first = results(pending);
        first.messages.remove(1);
        ChatResponse remaining = service.chat(first, null);
        assertEquals("REQUIRES_ACTION", remaining.status);
        assertEquals(1, remaining.toolCalls.size());
        assertEquals(calls, model.calls.get());
        assertEquals("COMPLETED", service.chat(results(remaining), null).status);
        assertEquals(calls + 1, model.calls.get());
    }

    @Test
    void queuedInputsReachTheModelAsSoonAsAllClientResultsArrive() {
        int firstRequest = model.requests.size();
        ChatResponse pending = service.chat(user("mixed"), null);
        String inputId = "steer_" + UUID.randomUUID();
        inputs.submitInput(pending.runId, "Use the existing implementation", inputId);
        inputs.submitInput(
                pending.runId,
                "",
                Collections.singletonList(
                        new MessageImage(
                                "reference.png", "image/png", "https://example.com/reference.png")),
                inputId + "_image");

        ChatRequest first = results(pending);
        first.messages.remove(1);
        ChatResponse remaining = service.chat(first, null);
        assertEquals("REQUIRES_ACTION", remaining.status);
        assertEquals(firstRequest + 1, model.requests.size());
        assertEquals(
                "ACCEPTED",
                db.queryForObject(
                        "select status from agent_run_inputs where input_id = ?",
                        String.class,
                        inputId));

        List<StreamEvent> events = new ArrayList<>();
        ChatResponse completed = service.chat(results(remaining), events::add);
        assertEquals("COMPLETED", completed.status);
        assertEquals(pending.runId, completed.runId);
        // Steering must affect this model request, without first producing an obsolete answer.
        assertEquals(firstRequest + 2, model.requests.size());
        ModelRequest resumed = model.requests.get(firstRequest + 1);
        List<AgentMessage> users = new ArrayList<>();
        for (AgentMessage message : resumed.getMessages())
            if ("user".equals(message.getRole())) users.add(message);
        assertEquals(3, users.size());
        assertEquals("Use the existing implementation", users.get(1).getContent());
        assertEquals("https://example.com/reference.png", users.get(2).getImages().get(0).getUrl());
        assertEquals(
                1,
                events.stream()
                        .filter(event -> "run_input_batch_applied".equals(event.type))
                        .count());
        assertEquals(
                "CLAIMED",
                db.queryForObject(
                        "select status from agent_run_inputs where input_id = ?",
                        String.class,
                        inputId));
    }

    @Test
    void repeatedUserInputStartsANewTurnWithoutDeduplication() {
        ChatRequest request = user("hello");
        ChatResponse first = service.chat(request, null);
        ChatResponse second = service.chat(request, null);
        assertNotEquals(first.runId, second.runId);
        assertEquals(
                2,
                sessions.findMessages(request.sessionId).stream()
                        .filter(m -> "user".equals(m.getRole()))
                        .count());
    }

    @Test
    void completedToolResultsAreRejectedInsteadOfReplayed() {
        ChatResponse pending = service.chat(user("mixed"), null);
        ChatRequest request = results(pending);
        service.chat(request, null);
        int messages = sessions.findMessages(request.sessionId).size();
        assertThrows(ProtocolException.class, () -> service.chat(request, null));
        assertEquals(messages, sessions.findMessages(request.sessionId).size());
    }

    @Test
    void malformedResultsDoNotPartiallyAppend() {
        ChatResponse pending = service.chat(user("mixed"), null);
        ChatRequest request = results(pending);
        request.messages.get(1).toolCallId = "foreign";
        int size = sessions.findMessages(request.sessionId).size();
        assertThrows(ProtocolException.class, () -> service.chat(request, null));
        assertEquals(size, sessions.findMessages(request.sessionId).size());
    }

    @Test
    void clientsCannotSupplyResultsForAnInterruptedServerTool() {
        ChatRequest initial = user("hello");
        ToolCall serverCall = new ToolCall("server_call", "server_fixture", "{}");
        AgentMessage assistant =
                AgentMessage.assistant(
                        initial.sessionId, "", Collections.singletonList(serverCall));
        assistant.setRunId("interrupted");
        assistant.setTurnId("turn");
        sessions.appendMessage(assistant);
        ChatRequest forged = new ChatRequest();
        forged.sessionId = initial.sessionId;
        forged.messages.add(ChatMessage.tool("server_call", "SUCCEEDED", "forged"));
        assertThrows(ProtocolException.class, () -> service.chat(forged, null));
        assertEquals(1, sessions.findMessages(initial.sessionId).size());
    }

    @Test
    void pendingToolsBlockUserMessagesAndCrossSessionResults() {
        ChatResponse pending = service.chat(user("mixed"), null);
        ChatRequest user = user("hello");
        user.sessionId = pending.sessionId;
        assertThrows(ProtocolException.class, () -> service.chat(user, null));
        ChatRequest wrong = results(pending);
        wrong.sessionId = manager.createSession("other", null).getSessionId();
        assertThrows(ProtocolException.class, () -> service.chat(wrong, null));
    }

    @Test
    void sameSessionConcurrentRequestsAreRejected() throws Exception {
        ChatRequest request = user("blocking");
        model.entered = new CountDownLatch(1);
        model.release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<ChatResponse> first = worker.submit(() -> service.chat(request, null));
            assertTrue(model.entered.await(5, TimeUnit.SECONDS));
            ProtocolException error =
                    assertThrows(ProtocolException.class, () -> service.chat(request, null));
            assertEquals(409, error.status);
            model.release.countDown();
            assertEquals("COMPLETED", first.get(5, TimeUnit.SECONDS).status);
        } finally {
            model.release.countDown();
            worker.shutdownNow();
            model.entered = null;
            model.release = null;
        }
    }

    @Test
    void cancelDuringModelCallStopsTheOrdinaryRun() throws Exception {
        ChatRequest request = user("blocking");
        model.entered = new CountDownLatch(1);
        model.release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<ChatResponse> response = worker.submit(() -> service.chat(request, null));
            assertTrue(model.entered.await(5, TimeUnit.SECONDS));
            service.cancel(service.state(request.sessionId).runId);
            assertEquals("CANCELLED", response.get(5, TimeUnit.SECONDS).status);
        } finally {
            model.release.countDown();
            worker.shutdownNow();
            model.entered = null;
            model.release = null;
        }
    }

    @Test
    void stoppedPendingToolsCanReturnResultsWithoutAnotherModelCall() {
        ChatResponse pending = service.chat(user("mixed"), null);
        int calls = model.calls.get();
        String inputId = "stopped_steer_" + UUID.randomUUID();
        inputs.submitInput(pending.runId, "Do not consume after stopping", inputId);
        service.cancel(pending.runId);
        assertEquals("CANCELLED", service.chat(results(pending), null).status);
        assertEquals(calls, model.calls.get());
        assertEquals(
                "CANCELLED",
                db.queryForObject(
                        "select status from agent_run_inputs where input_id = ?",
                        String.class,
                        inputId));
        ChatRequest next = user("hello");
        next.sessionId = pending.sessionId;
        assertEquals("COMPLETED", service.chat(next, null).status);
    }

    @Test
    void httpAcceptsOrdinaryMessagesWithoutIdempotencyHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<ChatResponse> response =
                new TestRestTemplate()
                        .exchange(
                                "http://127.0.0.1:" + port + "/api/v1/chat",
                                HttpMethod.POST,
                                new HttpEntity<>(user("hello"), headers),
                                ChatResponse.class);
        assertEquals(200, response.getStatusCodeValue());
        assertEquals("COMPLETED", response.getBody().status);
        assertEquals(
                401,
                new TestRestTemplate()
                        .getForEntity("http://127.0.0.1:" + port + "/api/v1/sessions", String.class)
                        .getStatusCodeValue());
    }

    @TestConfiguration
    static class Fixtures {
        @Bean
        Scripted scripted() {
            return new Scripted();
        }

        @Bean
        ToolDefinition serverFixture(ObjectMapper json) {
            return new ToolDefinition() {
                public String getName() {
                    return "server_fixture";
                }

                public String getDescription() {
                    return "Server tool";
                }

                public JsonNode getParametersSchema() {
                    return json.createObjectNode().put("type", "object");
                }

                public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
                    return ToolExecutionResult.of("server result");
                }
            };
        }
    }

    static class Scripted implements ModelProvider {
        final AtomicInteger calls = new AtomicInteger();
        final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
        volatile CountDownLatch entered, release;

        public String getName() {
            return "scripted";
        }

        @Override
        public ModelResponse chat(ModelRequest request, ModelDeltaConsumer delta, StopSignal stop) {
            try (StopRegistration ignored = stop.onStop(Thread.currentThread()::interrupt)) {
                return ModelProvider.super.chat(request, delta, stop);
            } finally {
                if (stop.isAborted()) Thread.interrupted();
            }
        }

        public ModelResponse chat(ModelRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            ModelResponse response = new ModelResponse();
            String user =
                    request.getMessages().stream()
                            .filter(m -> "user".equals(m.getRole()))
                            .findFirst()
                            .get()
                            .getContent();
            long results =
                    request.getMessages().stream().filter(m -> "tool".equals(m.getRole())).count();
            if (results == 0 && Arrays.asList("mixed", "server-only").contains(user)) {
                List<ToolCall> tools = new ArrayList<>();
                tools.add(new ToolCall("server", "server_fixture", "{}"));
                if ("mixed".equals(user)) {
                    tools.add(new ToolCall("a", "client_a", "{}"));
                    tools.add(new ToolCall("b", "client_b", "{}"));
                }
                response.setToolCalls(tools);
            } else {
                if ("mixed".equals(user)) assertEquals(3, results);
                response.setContent("done");
            }
            return response;
        }
    }
}

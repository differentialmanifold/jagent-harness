package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.client.*;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerStore;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:sqlite:file:runner-tests?mode=memory&cache=shared",
            "spring.datasource.driver-class-name=org.sqlite.JDBC",
            "spring.datasource.hikari.maximum-pool-size=1",
            "harness.model.provider=scripted",
            "harness.model.model=test",
            "harness.compaction.enabled=false",
            "harness.protocol.token=test-token",
            "harness.store.jdbc.application-id=runner-tests"
        })
@Import(ConversationProtocolTest.Fixtures.class)
class RunnerProtocolTest {
    @Autowired RunnerService runners;
    @Autowired RunnerStore store;
    @Autowired SessionManager sessions;
    @Autowired SessionStore history;
    @Autowired ChatService chat;
    @LocalServerPort int port;
    final ObjectMapper json = new ObjectMapper();
    final String secret = "secret-for-tests-with-at-least-32-characters";

    HttpAgentTransport http(String id) throws Exception {
        return new HttpAgentTransport("http://127.0.0.1:" + port, "test-token")
                .header("X-Runner-Id", id)
                .header("X-Runner-Secret", secret);
    }

    RunnerInfo register() throws Exception {
        RunnerInfo info = new RunnerInfo();
        info.runnerId = "runner_" + UUID.randomUUID().toString();
        info.name = "test runner";
        info.workspace = "/remote/workspace";
        for (String name : Arrays.asList("client_a", "client_b")) {
            ToolDescriptor tool = new ToolDescriptor();
            tool.name = name;
            tool.parameters.put("type", "object");
            info.tools.add(tool);
        }
        http(info.runnerId).management("POST", "/api/v1/runners", info);
        return info;
    }

    ChatRequest request(RunnerInfo info) throws Exception {
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("title", "Remote test");
        create.put("runnerId", info.runnerId);
        create.put("workspacePath", "/must/not/be/used");
        JsonNode session = http(info.runnerId).management("POST", "/api/v1/sessions", create);
        assertEquals(info.runnerId, session.path("runnerId").asText());
        assertEquals(info.workspace, session.path("workspacePath").asText());
        ChatRequest request = new ChatRequest();
        request.sessionId = session.path("sessionId").asText();
        request.runId = "run_" + UUID.randomUUID();
        request.messages.add(ChatMessage.user("mixed"));
        return request;
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path runnerHome;

    @Test
    void sdkHostsWorkspaceFreeToolsThroughPersistedServerDispatch() throws Exception {
        Object device = new Object();
        java.util.concurrent.atomic.AtomicInteger effects =
                new java.util.concurrent.atomic.AtomicInteger();
        List<ToolDefinition> local = new ArrayList<>();
        for (String name : Arrays.asList("client_a", "client_b")) {
            local.add(
                    new ToolDefinition() {
                        public String getName() {
                            return name;
                        }

                        public String getDescription() {
                            return "Device API";
                        }

                        public JsonNode getParametersSchema() {
                            return json.createObjectNode().put("type", "object");
                        }

                        public ToolExecutionResult execute(ToolContext context, JsonNode args) {
                            assertNull(context.getWorkspaceRoot());
                            assertSame(device, context.requireLocalContext(Object.class));
                            effects.incrementAndGet();
                            return ToolExecutionResult.success(
                                    Collections.singletonMap("device", "ready"));
                        }
                    });
        }
        ExecutorService receiver = Executors.newSingleThreadExecutor();
        try (AgentRunner runner =
                AgentRunner.builder("http://127.0.0.1:" + port, runnerHome)
                        .token("test-token")
                        .name("Device without filesystem tools")
                        .tools(local)
                        .toolContext(device)
                        .build()) {
            Future<?> running =
                    receiver.submit(
                            () -> {
                                runner.serve();
                                return null;
                            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (store.runner(runner.runnerId()) == null && System.nanoTime() < deadline)
                Thread.sleep(20);
            RunnerInfo registered = store.runner(runner.runnerId());
            assertNotNull(registered);
            assertNull(registered.workspace);
            HttpAgentTransport frontend =
                    new HttpAgentTransport("http://127.0.0.1:" + port, "test-token");
            JsonNode created =
                    frontend.management(
                            "POST",
                            "/api/v1/sessions",
                            Collections.singletonMap("runnerId", runner.runnerId()));
            assertTrue(created.path("workspacePath").isNull());
            ChatRequest request = new ChatRequest();
            request.sessionId = created.path("sessionId").asText();
            request.runId = "run_" + UUID.randomUUID();
            request.messages.add(ChatMessage.user("mixed"));
            runners.submit(request);
            while (!"COMPLETED".equals(store.job(request.runId).status)
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals("COMPLETED", store.job(request.runId).status);
            assertNull(store.job(request.runId).workspace);
            assertEquals(2, effects.get());
            runner.close();
            running.get(5, TimeUnit.SECONDS);
        } finally {
            receiver.shutdownNow();
        }
    }

    @Test
    void reRegistrationReleasesThePreviousProcessesLongPoll() throws Exception {
        RunnerInfo info = register();
        org.springframework.web.context.request.async.DeferredResult<RunnerPoll> previous =
                runners.poll(info.runnerId, null);
        assertFalse(previous.hasResult());
        runners.register(info, secret);
        assertTrue(previous.hasResult());
        ChatRequest request = request(info);
        runners.submit(request);
        RunnerPoll next =
                json.treeToValue(
                        http(info.runnerId)
                                .management(
                                        "GET", "/api/v1/runners/" + info.runnerId + "/poll", null),
                        RunnerPoll.class);
        assertEquals(request.runId, next.job.request.runId);
    }

    @Test
    void renamingPreservesRemoteExecutionTarget() throws Exception {
        RunnerInfo info = register();
        ChatRequest request = request(info);
        Map<String, String> rename = new LinkedHashMap<>();
        rename.put("sessionId", request.sessionId);
        rename.put("title", "Title from the first prompt");
        JsonNode renamed =
                http(info.runnerId).management("POST", "/api/v1/sessions/rename", rename);
        assertEquals(info.runnerId, renamed.path("runnerId").asText());
        assertEquals(info.workspace, renamed.path("workspacePath").asText());
    }

    @Test
    void interruptedJobAcceptsSavedResultsWithoutReplayingUserMessage() throws Exception {
        RunnerInfo info = register();
        HttpAgentTransport http = http(info.runnerId);
        ChatRequest request = request(info);
        runners.submit(request);
        RunnerPoll claimed =
                json.treeToValue(
                        http.management("GET", "/api/v1/runners/" + info.runnerId + "/poll", null),
                        RunnerPoll.class);
        ChatResponse pending = http.chat(claimed.job.request, null);
        assertEquals("REQUIRES_ACTION", pending.status);
        ChatResponse interrupted = new ChatResponse();
        interrupted.runId = request.runId;
        interrupted.sessionId = request.sessionId;
        interrupted.status = "INTERRUPTED";
        http.management("POST", "/api/v1/runners/" + info.runnerId + "/complete", interrupted);
        assertThrows(ProtocolException.class, () -> runners.submit(request));
        String recoverPath =
                "/api/v1/runners/" + info.runnerId + "/runs/" + request.runId + "/recover";
        http.management("POST", recoverPath, Collections.emptyMap());
        assertEquals(
                409,
                assertThrows(
                                AgentHttpException.class,
                                () -> http.management("POST", recoverPath, Collections.emptyMap()))
                        .status);
        ChatRequest results = new ChatRequest();
        results.sessionId = request.sessionId;
        results.runId = request.runId;
        for (ToolInvocation tool : pending.toolCalls)
            results.messages.add(
                    ChatMessage.tool(tool.toolCallId, "SUCCEEDED", "saved local result"));
        ChatResponse completed = http.chat(results, null);
        assertEquals("COMPLETED", completed.status);
        http.management("POST", "/api/v1/runners/" + info.runnerId + "/complete", completed);
        assertEquals("COMPLETED", store.job(request.runId).status);
        assertEquals(
                1,
                history.findMessages(request.sessionId).stream()
                        .filter(m -> "user".equals(m.getRole()))
                        .count());
        ChatRequest next = new ChatRequest();
        next.sessionId = request.sessionId;
        next.runId = "run_" + UUID.randomUUID();
        next.messages.add(ChatMessage.user("next"));
        runners.submit(next);
        assertEquals("QUEUED", store.job(next.runId).status);
        runners.cancel(next.runId);
    }

    @Test
    void longPollWakesAndClientResultsUseNormalConversation() throws Exception {
        RunnerInfo info = register();
        HttpAgentTransport http = http(info.runnerId);
        ChatRequest request = request(info);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> poll =
                    executor.submit(
                            () ->
                                    http.management(
                                            "GET",
                                            "/api/v1/runners/" + info.runnerId + "/poll",
                                            null));
            runners.submit(request);
            RunnerPoll claimed = json.treeToValue(poll.get(5, TimeUnit.SECONDS), RunnerPoll.class);
            assertEquals(request.runId, claimed.job.request.runId);
            assertEquals("CLAIMED", store.job(request.runId).status);
            assertThrows(ProtocolException.class, () -> runners.submit(request));
            List<ToolDefinition> local = new ArrayList<>();
            for (String name : Arrays.asList("client_a", "client_b"))
                local.add(
                        new ToolDefinition() {
                            public String getName() {
                                return name;
                            }

                            public String getDescription() {
                                return name;
                            }

                            public JsonNode getParametersSchema() {
                                return json.createObjectNode().put("type", "object");
                            }

                            public ToolExecutionResult execute(ToolContext context, JsonNode args) {
                                return ToolExecutionResult.success("local result");
                            }
                        });
            ChatResponse result =
                    new AgentClient(http, local).run(claimed.job.request, new ClientRunOptions());
            assertEquals("COMPLETED", result.status);
            http.management("POST", "/api/v1/runners/" + info.runnerId + "/complete", result);
            http.management("POST", "/api/v1/runners/" + info.runnerId + "/complete", result);
            assertEquals("COMPLETED", store.job(request.runId).status);
            assertEquals(
                    3,
                    history.findMessages(request.sessionId).stream()
                            .filter(m -> "tool".equals(m.getRole()))
                            .count());
            assertEquals(
                    1,
                    history.findMessages(request.sessionId).stream()
                            .filter(m -> "user".equals(m.getRole()))
                            .count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void queuedCancellationNeverStartsModel() throws Exception {
        RunnerInfo info = register();
        ChatRequest request = request(info);
        runners.submit(request);
        http(info.runnerId).stop(request.runId);
        assertEquals("CANCELLED", store.job(request.runId).response.status);
        assertTrue(history.findMessages(request.sessionId).isEmpty());
    }

    @Test
    void cancellationIsDeliveredWhileWorkerIsBusy() throws Exception {
        RunnerInfo info = register();
        ChatRequest request = request(info);
        runners.submit(request);
        http(info.runnerId).management("GET", "/api/v1/runners/" + info.runnerId + "/poll", null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> waiting =
                    executor.submit(
                            () ->
                                    http(info.runnerId)
                                            .management(
                                                    "GET",
                                                    "/api/v1/runners/"
                                                            + info.runnerId
                                                            + "/poll?activeRun="
                                                            + request.runId,
                                                    null));
            http(info.runnerId).stop(request.runId);
            assertEquals(
                    request.runId,
                    waiting.get(5, TimeUnit.SECONDS).path("cancelledRuns").get(0).asText());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void credentialsAndSessionAssignmentAreEnforced() throws Exception {
        RunnerInfo owner = register(), other = register();
        ChatRequest request = request(owner);
        runners.submit(request);
        http(owner.runnerId).management("GET", "/api/v1/runners/" + owner.runnerId + "/poll", null);
        AgentHttpException error =
                assertThrows(
                        AgentHttpException.class, () -> http(other.runnerId).chat(request, null));
        assertEquals(409, error.status);
        assertEquals(
                400,
                assertThrows(
                                AgentHttpException.class,
                                () ->
                                        new HttpAgentTransport(
                                                        "http://127.0.0.1:" + port, "test-token")
                                                .management("POST", "/api/v1/runners", owner))
                        .status);
        RunnerInfo altered = json.convertValue(owner, RunnerInfo.class);
        altered.workspace = "/another/workspace";
        assertThrows(ProtocolException.class, () -> runners.register(altered, secret));
        store.heartbeat(other.runnerId, 0);
        assertThrows(ProtocolException.class, () -> runners.submit(request(other)));
    }

    @Test
    void claimedJobSurvivesAnotherPollWithoutCreatingAnotherTurn() throws Exception {
        RunnerInfo info = register();
        ChatRequest request = request(info);
        runners.submit(request);
        JsonNode first =
                http(info.runnerId)
                        .management("GET", "/api/v1/runners/" + info.runnerId + "/poll", null);
        JsonNode again =
                http(info.runnerId)
                        .management("GET", "/api/v1/runners/" + info.runnerId + "/poll", null);
        assertEquals(first.path("job").path("request"), again.path("job").path("request"));
        assertTrue(history.findMessages(request.sessionId).isEmpty());
    }
}

package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.client.*;
import io.github.differentialmanifold.jagentharness.core.execution.ConversationService;
import io.github.differentialmanifold.jagentharness.core.execution.ProtocolException;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
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
    @Autowired ConversationService chat;
    @Autowired ConversationProtocolTest.Scripted model;
    @LocalServerPort int port;
    final ObjectMapper json = new ObjectMapper();
    final String secret = "secret-for-tests-with-at-least-32-characters";

    @Test
    void publicChatRoutesProjectsAndPrivateChatOnlyAdvancesClaimedRunnerWork() throws Exception {
        HttpAgentTransport frontend =
                new HttpAgentTransport("http://127.0.0.1:" + port, "test-token");
        ChatRequest serverOnly = new ChatRequest();
        serverOnly.sessionId = frontend.createSession("Server tools", null);
        serverOnly.messages.add(ChatMessage.user("server-only"));
        assertEquals("COMPLETED", frontend.chat(serverOnly, null).status);
        assertEquals(
                403,
                assertThrows(
                                AgentHttpException.class,
                                () ->
                                        frontend.management(
                                                "POST", "/api/v1/runners/chat", serverOnly))
                        .status);

        for (boolean stream : Arrays.asList(false, true)) {
            RunnerInfo runner = register();
            HttpAgentTransport executor = http(runner.runnerId);
            ChatRequest request = request(runner);
            request.runId = null; // Public callers need only a session and message.
            request.stream = stream;
            ExecutorService worker = Executors.newSingleThreadExecutor();
            List<StreamEvent> events = new CopyOnWriteArrayList<>();
            try {
                Future<ChatResponse> reply =
                        worker.submit(() -> frontend.chat(request, events::add));
                RunnerPoll poll =
                        json.treeToValue(
                                executor.management(
                                        "GET",
                                        "/api/v1/runners/" + runner.runnerId + "/poll",
                                        null),
                                RunnerPoll.class);
                assertNotNull(poll.job);
                assertEquals(request.sessionId, poll.job.request.sessionId);
                assertEquals("RUNNING", frontend.state(request.sessionId).status);
                assertTrue(history.findMessages(request.sessionId).isEmpty());

                ChatResponse tools = executor.chat(poll.job.request, null);
                assertEquals("REQUIRES_ACTION", tools.status);
                assertEquals("REQUIRES_ACTION", executor.state(request.sessionId).status);
                ChatRequest results = new ChatRequest();
                results.sessionId = request.sessionId;
                results.runId = tools.runId;
                for (ToolInvocation call : tools.toolCalls)
                    results.messages.add(
                            ChatMessage.tool(call.toolCallId, "SUCCEEDED", "local result"));
                assertEquals(
                        400,
                        assertThrows(AgentHttpException.class, () -> frontend.chat(results, null))
                                .status);
                ChatResponse answer = executor.chat(results, null);
                assertEquals("COMPLETED", answer.status);
                executor.management(
                        "POST", "/api/v1/runners/" + runner.runnerId + "/complete", answer);
                ChatResponse received = reply.get(5, TimeUnit.SECONDS);
                assertEquals(answer.answer, received.answer);
                assertEquals(answer.runId, received.runId);
                assertEquals(
                        1,
                        history.findMessages(request.sessionId).stream()
                                .filter(m -> "user".equals(m.getRole()))
                                .count());
                if (stream) assertTrue(events.stream().anyMatch(e -> "agent_start".equals(e.type)));
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    void observingOrDisconnectingDoesNotReplaceOtherObserversOrCancelWork() throws Exception {
        RunnerInfo runner = register();
        ChatRequest request = request(runner);
        io.github.differentialmanifold.jagentharness.core.execution.RunHandle first =
                runners.submit(request);
        io.github.differentialmanifold.jagentharness.core.execution.RunHandle second =
                runners.watch(request.runId, null);
        first.close();
        assertFalse(store.job(request.runId).cancelled);
        runners.cancel(request.runId);
        assertEquals("CANCELLED", second.result().get(5, TimeUnit.SECONDS).status);
        assertEquals(
                "CANCELLED",
                runners.watch(request.runId, null).result().get(5, TimeUnit.SECONDS).status);
    }

    HttpAgentTransport http(String id) throws Exception {
        return new HttpAgentTransport("http://127.0.0.1:" + port, "test-token")
                .runnerProtocol()
                .header("X-Runner-Id", id)
                .header("X-Runner-Secret", secret);
    }

    RunnerInfo register() throws Exception {
        RunnerInfo info = new RunnerInfo();
        info.runnerId = "runner_" + UUID.randomUUID().toString();
        info.name = "test runner";
        info.workspaces = true;
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
        create.put("workspacePath", "/remote/workspace");
        JsonNode session = http(info.runnerId).management("POST", "/api/v1/sessions", create);
        assertEquals(info.runnerId, session.path("runnerId").asText());
        assertEquals("/remote/workspace", session.path("workspacePath").asText());
        ChatRequest request = new ChatRequest();
        request.sessionId = session.path("sessionId").asText();
        request.runId = "run_" + UUID.randomUUID();
        request.messages.add(ChatMessage.user("mixed"));
        return request;
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path runnerHome;

    @Test
    void sdkHostsWorkspaceFreeToolsAndSteersBeforeTheNextModelRequest() throws Exception {
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

                        public ToolExecutionResult execute(ToolContext context, JsonNode args)
                                throws Exception {
                            assertNull(context.getWorkspaceRoot());
                            assertSame(device, context.requireLocalContext(Object.class));
                            if (effects.incrementAndGet() == 1) {
                                Map<String, String> input = new LinkedHashMap<>();
                                input.put("inputId", "steer_" + UUID.randomUUID());
                                input.put("content", "Keep the existing device configuration");
                                new HttpAgentTransport("http://127.0.0.1:" + port, "test-token")
                                        .management(
                                                "POST",
                                                "/api/v1/runs/" + context.getRunId() + "/inputs",
                                                input);
                            }
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
            assertFalse(registered.workspaces);
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
            List<ModelRequest> modelRequests = new ArrayList<>();
            for (ModelRequest call : model.requests)
                if (request.sessionId.equals(call.getMessages().get(0).getSessionId()))
                    modelRequests.add(call);
            assertEquals(2, modelRequests.size());
            List<AgentMessage> messages = modelRequests.get(1).getMessages();
            assertEquals("user", messages.get(messages.size() - 1).getRole());
            assertEquals(
                    "Keep the existing device configuration",
                    messages.get(messages.size() - 1).getContent());
            runner.close();
            running.get(5, TimeUnit.SECONDS);
        } finally {
            receiver.shutdownNow();
        }
    }

    @Test
    void sessionsOnOneRunnerExecuteConcurrentlyAndCancelIndependently() throws Exception {
        Map<String, CountDownLatch> entered = new ConcurrentHashMap<>();
        Map<String, CountDownLatch> release = new ConcurrentHashMap<>();
        Set<String> stopped = ConcurrentHashMap.newKeySet();
        List<ToolDefinition> tools = new ArrayList<>();
        for (String name : Arrays.asList("client_a", "client_b")) {
            tools.add(
                    new ToolDefinition() {
                        public String getName() {
                            return name;
                        }

                        public String getDescription() {
                            return "Concurrent test tool";
                        }

                        public JsonNode getParametersSchema() {
                            return json.createObjectNode().put("type", "object");
                        }

                        public ToolExecutionResult execute(ToolContext context, JsonNode args)
                                throws Exception {
                            if ("client_a".equals(name)) {
                                String session = context.getSessionId();
                                try (io.github.differentialmanifold.jagentharness.core.agent
                                                .StopRegistration
                                        ignored =
                                                context.getStopSignal()
                                                        .onStop(
                                                                () -> {
                                                                    stopped.add(session);
                                                                    release.get(session)
                                                                            .countDown();
                                                                })) {
                                    entered.get(session).countDown();
                                    assertTrue(release.get(session).await(15, TimeUnit.SECONDS));
                                }
                            }
                            return ToolExecutionResult.success(context.getSessionId());
                        }
                    });
        }
        ExecutorService receiver = Executors.newSingleThreadExecutor();
        try (AgentRunner runner =
                AgentRunner.builder("http://127.0.0.1:" + port, runnerHome)
                        .token("test-token")
                        .tools(tools)
                        .build()) {
            Future<?> receiving =
                    receiver.submit(
                            () -> {
                                runner.serve();
                                return null;
                            });
            awaitCondition(() -> store.runner(runner.runnerId()) != null);
            HttpAgentTransport frontend =
                    new HttpAgentTransport("http://127.0.0.1:" + port, "test-token");
            List<ChatRequest> requests = new ArrayList<>();
            String projectId = null;
            for (int i = 0; i < 6; i++) {
                Map<String, Object> create = new LinkedHashMap<>();
                create.put("runnerId", runner.runnerId());
                if (projectId != null) create.put("projectId", projectId);
                JsonNode session = frontend.management("POST", "/api/v1/sessions", create);
                projectId = session.path("projectId").asText();
                ChatRequest request = new ChatRequest();
                request.sessionId = session.path("sessionId").asText();
                request.runId = "run_" + UUID.randomUUID();
                request.messages.add(ChatMessage.user("mixed"));
                entered.put(request.sessionId, new CountDownLatch(1));
                release.put(request.sessionId, new CountDownLatch(1));
                requests.add(request);
            }
            for (ChatRequest request : requests) runners.submit(request);
            for (ChatRequest request : requests)
                assertTrue(
                        entered.get(request.sessionId).await(10, TimeUnit.SECONDS),
                        "All six sessions must execute the same tool before any finishes");
            ChatRequest first = requests.get(0);
            ChatRequest overlapping = new ChatRequest();
            overlapping.sessionId = first.sessionId;
            overlapping.runId = "run_" + UUID.randomUUID();
            overlapping.messages.add(ChatMessage.user("another turn"));
            assertThrows(ProtocolException.class, () -> runners.submit(overlapping));
            frontend.stop(first.runId);
            awaitCondition(() -> "CANCELLED".equals(store.job(first.runId).status));
            assertEquals(Collections.singleton(first.sessionId), stopped);
            List<ChatRequest> remaining = requests.subList(1, requests.size());
            for (ChatRequest request : remaining) {
                assertEquals("CLAIMED", store.job(request.runId).status);
                release.get(request.sessionId).countDown();
            }
            awaitCondition(
                    () ->
                            remaining.stream()
                                    .allMatch(
                                            request ->
                                                    "COMPLETED"
                                                            .equals(
                                                                    store.job(request.runId)
                                                                            .status)));
            for (ChatRequest request : requests) {
                assertEquals(
                        1,
                        history.findMessages(request.sessionId).stream()
                                .filter(message -> "user".equals(message.getRole()))
                                .count());
                assertTrue(
                        history.findMessages(request.sessionId).stream()
                                .filter(
                                        message ->
                                                "tool".equals(message.getRole())
                                                        && "client_a".equals(message.getToolName()))
                                .allMatch(
                                        message ->
                                                message.getContent().contains(request.sessionId)));
            }
            runner.close();
            receiving.get(5, TimeUnit.SECONDS);
        } finally {
            release.values().forEach(CountDownLatch::countDown);
            receiver.shutdownNow();
        }
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void projectsPersistIndependentWorkspacesAndToolSelections() throws Exception {
        RunnerInfo info = register();
        ChatRequest first = request(info);
        String projectId = sessions.requireSession(first.sessionId).getProjectId();
        HttpAgentTransport frontend = http(info.runnerId);
        frontend.management(
                "PUT",
                "/api/v1/runners/projects/" + projectId + "/tools",
                Collections.singletonMap("enabledTools", Collections.singletonList("client_a")));
        // A submitted catalog cannot enable a project-disabled tool.
        first.clientTools = info.tools;
        runners.submit(first);
        RunnerJob frozen = store.job(first.runId);
        assertEquals("/remote/workspace", frozen.workspace);
        assertEquals(1, frozen.request.clientTools.size());
        assertEquals("client_a", frozen.request.clientTools.get(0).name);
        frontend.management(
                "PUT",
                "/api/v1/runners/projects/" + projectId + "/tools",
                Collections.singletonMap("enabledTools", Collections.emptyList()));
        assertEquals(1, store.job(first.runId).request.clientTools.size());
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("projectId", projectId);
        create.put("workspacePath", "/ignored-for-existing-project");
        JsonNode nextSession = frontend.management("POST", "/api/v1/sessions", create);
        assertEquals("/remote/workspace", nextSession.path("workspacePath").asText());
        assertEquals(info.runnerId, nextSession.path("runnerId").asText());
        ChatRequest next = new ChatRequest();
        next.sessionId = nextSession.path("sessionId").asText();
        next.runId = "run_" + UUID.randomUUID();
        next.messages.add(ChatMessage.user("next"));
        runners.submit(next);
        assertTrue(store.job(next.runId).request.clientTools.isEmpty());
        create.remove("projectId");
        create.put("runnerId", info.runnerId);
        create.put("workspacePath", "D:\\another-project");
        JsonNode second = frontend.management("POST", "/api/v1/sessions", create);
        RunnerProject secondProject = store.project(second.path("projectId").asText());
        assertEquals("D:\\another-project", secondProject.workspace);
        assertEquals(2, secondProject.enabledTools.size());
        assertTrue(store.project(projectId).enabledTools.isEmpty());
        JsonNode context =
                frontend.management(
                        "POST",
                        "/api/v1/agent/context",
                        Collections.singletonMap("sessionId", first.sessionId));
        for (JsonNode tool : context.path("tools"))
            assertFalse(tool.path("name").asText().startsWith("client_"));
        JsonNode preview =
                frontend.management(
                        "POST",
                        "/api/v1/agent/prompt-preview",
                        Collections.singletonMap("sessionId", first.sessionId));
        assertFalse(preview.path("prompt").asText().contains("client_a"));
        assertFalse(preview.path("prompt").asText().contains("client_b"));
        assertEquals("/remote/workspace", preview.path("workspaceRoot").asText());
        assertEquals(
                400,
                assertThrows(
                                AgentHttpException.class,
                                () ->
                                        frontend.management(
                                                "PUT",
                                                "/api/v1/runners/projects/" + projectId + "/tools",
                                                Collections.emptyMap()))
                        .status);
        assertEquals(
                400,
                assertThrows(
                                AgentHttpException.class,
                                () ->
                                        frontend.management(
                                                "PUT",
                                                "/api/v1/runners/projects/" + projectId + "/tools",
                                                Collections.singletonMap(
                                                        "enabledTools",
                                                        Collections.singletonList(
                                                                "not_registered"))))
                        .status);
        runners.cancel(first.runId);
        runners.cancel(next.runId);
    }

    @Test
    void frontendBrowsesRunnerFilesystemThroughOutboundPoll() throws Exception {
        java.nio.file.Path folder =
                java.nio.file.Files.createDirectories(runnerHome.resolve("remote-project"));
        java.nio.file.Files.createDirectory(folder.resolve("src"));
        ToolDefinition tool =
                new ToolDefinition() {
                    public String getName() {
                        return "unused";
                    }

                    public String getDescription() {
                        return "unused";
                    }

                    public JsonNode getParametersSchema() {
                        return json.createObjectNode().put("type", "object");
                    }

                    public ToolExecutionResult execute(ToolContext context, JsonNode args) {
                        throw new AssertionError("Browsing is not a model tool invocation");
                    }
                };
        ExecutorService receiver = Executors.newSingleThreadExecutor();
        try (AgentRunner runner =
                AgentRunner.builder("http://127.0.0.1:" + port, runnerHome.resolve("state"))
                        .token("test-token")
                        .tools(Collections.singletonList(tool))
                        .workspaces(new LocalWorkspaces())
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
            String endpoint = "/api/v1/runners/" + runner.runnerId() + "/workspaces?path=";
            HttpAgentTransport frontend =
                    new HttpAgentTransport("http://127.0.0.1:" + port, "test-token");
            JsonNode listing =
                    frontend.management(
                            "GET",
                            endpoint + java.net.URLEncoder.encode(folder.toString(), "UTF-8"),
                            null);
            assertEquals(folder.toRealPath().toString(), listing.path("path").asText());
            assertEquals(
                    folder.resolve("src").toRealPath().toString(),
                    listing.path("directories").get(0).asText());
            assertEquals(
                    400,
                    assertThrows(
                                    AgentHttpException.class,
                                    () ->
                                            frontend.management(
                                                    "GET",
                                                    endpoint
                                                            + java.net.URLEncoder.encode(
                                                                    folder.resolve("missing")
                                                                            .toString(),
                                                                    "UTF-8"),
                                                    null))
                            .status);
            runner.close();
            running.get(5, TimeUnit.SECONDS);
        } finally {
            receiver.shutdownNow();
        }
    }

    @Test
    void reRegistrationReleasesThePreviousProcessesLongPoll() throws Exception {
        RunnerInfo info = register();
        CompletableFuture<RunnerPoll> previous = runners.poll(info.runnerId, null);
        assertFalse(previous.isDone());
        runners.register(info, secret);
        assertTrue(previous.isDone());
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
        assertEquals("/remote/workspace", renamed.path("workspacePath").asText());
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
        altered.workspaces = false;
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

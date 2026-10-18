package io.github.differentialmanifold.jagentharness.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AgentRunnerTest {
    @TempDir Path home;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void customToolsWorkWithoutWorkspaceAndIdentitySurvivesRestart() throws Exception {
        Object device = new Object();
        AtomicInteger effects = new AtomicInteger();
        ToolDefinition tool =
                tool(
                        (context, args) -> {
                            assertNull(context.getWorkspaceRoot());
                            assertSame(device, context.requireLocalContext(Object.class));
                            effects.incrementAndGet();
                            return ToolExecutionResult.success(
                                    Collections.singletonMap("temperature", 24));
                        });
        try (Server server = new Server()) {
            String id;
            try (AgentRunner runner =
                    AgentRunner.builder(server.url(), home)
                            .tools(Collections.singletonList(tool))
                            .toolContext(device)
                            .toolDiagnostics(
                                    Collections.singletonMap(tool.getName(), "Native device API"))
                            .token("not-stored")
                            .build()) {
                id = runner.runnerId();
                server.job = job(id, tool);
                server.jobs.add(server.job);
                Future<?> receive =
                        server.threads.submit(
                                () -> {
                                    runner.serve();
                                    return null;
                                });
                ChatResponse result = server.completed.poll(5, TimeUnit.SECONDS);
                assertNotNull(result);
                assertEquals("COMPLETED", result.status);
                assertFalse(server.registration.get().workspaces);
                assertEquals(
                        "Native device API",
                        server.registration.get().toolDiagnostics.get(tool.getName()));
                assertEquals(1, effects.get());
                assertEquals(2, server.chats.size());
                assertEquals("tool", server.chats.get(1).messages.get(0).role);
                assertEquals("SUCCEEDED", server.chats.get(1).messages.get(0).status);
                assertEquals(
                        24,
                        ((Map<?, ?>) server.chats.get(1).messages.get(0).content)
                                .get("temperature"));
                // Redelivery returns the saved response, without replaying the prompt or the tool.
                server.jobs.add(server.job);
                assertNotNull(server.completed.poll(5, TimeUnit.SECONDS));
                assertEquals(2, server.chats.size());
                runner.close();
                receive.get(5, TimeUnit.SECONDS);
            }
            try (AgentRunner restarted =
                    AgentRunner.builder(server.url(), home)
                            .tools(Collections.singletonList(tool))
                            .build()) {
                assertEquals(id, restarted.runnerId());
            }
            assertFalse(
                    new String(
                                    Files.readAllBytes(home.resolve("config.json")),
                                    StandardCharsets.UTF_8)
                            .contains("not-stored"));
        }
    }

    @Test
    void longPollingContinuesWhileToolRunsAndDeliversCancellation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        ToolDefinition tool =
                tool(
                        (context, args) -> {
                            try (io.github.differentialmanifold.jagentharness.core.agent
                                            .StopRegistration
                                    ignored = context.getStopSignal().onStop(stopped::countDown)) {
                                started.countDown();
                                assertTrue(stopped.await(5, TimeUnit.SECONDS));
                                return ToolExecutionResult.failure("cancelled locally");
                            }
                        });
        try (Server server = new Server();
                AgentRunner runner =
                        AgentRunner.builder(server.url(), home)
                                .tools(Collections.singletonList(tool))
                                .build()) {
            server.job = job(runner.runnerId(), tool);
            server.jobs.add(server.job);
            Future<?> receive =
                    server.threads.submit(
                            () -> {
                                runner.serve();
                                return null;
                            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            server.cancelled.set(true);
            assertNotNull(server.completed.poll(5, TimeUnit.SECONDS));
            assertEquals("FAILED", server.chats.get(1).messages.get(0).status);
            runner.close();
            receive.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void recoverSubmitsSavedResultsWithoutReexecutingTools() throws Exception {
        AtomicInteger effects = new AtomicInteger();
        ToolDefinition tool =
                tool(
                        (context, args) -> {
                            effects.incrementAndGet();
                            return ToolExecutionResult.success("new result");
                        });
        try (Server server = new Server();
                AgentRunner runner =
                        AgentRunner.builder(server.url(), home)
                                .tools(Collections.singletonList(tool))
                                .build()) {
            server.job = job(runner.runnerId(), tool);
            ToolJournal journal = new ToolJournal(home.resolve("results"));
            journal.wrap(tool)
                    .execute(
                            new ToolContext("s", "r", "t").forToolCall("c", "device_status"),
                            json.createObjectNode());
            assertEquals("COMPLETED", runner.recover("s").status);
            assertEquals(1, effects.get());
            assertEquals(1, server.chats.size());
            assertEquals("tool", server.chats.get(0).messages.get(0).role);
            assertEquals("new result", server.chats.get(0).messages.get(0).content);
        }
    }

    @Test
    void stateDirectoryIsExclusiveAndCannotBeReboundToAnotherServer() throws Exception {
        ToolDefinition tool = tool((context, args) -> ToolExecutionResult.success("ok"));
        try (AgentRunner runner =
                AgentRunner.builder("https://agent.example.com", home)
                        .tools(Collections.singletonList(tool))
                        .build()) {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            AgentRunner.builder("https://agent.example.com", home)
                                    .tools(Collections.singletonList(tool))
                                    .build());
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AgentRunner.builder("https://another.example.com", home)
                                .tools(Collections.singletonList(tool))
                                .build());
        try (AgentRunner restarted =
                AgentRunner.builder("https://agent.example.com", home)
                        .tools(Collections.singletonList(tool))
                        .build()) {
            assertNotNull(restarted.runnerId());
        }
    }

    @Test
    void plainHttpBeyondLoopbackIsAllowedWithAWarning() throws Exception {
        ToolDefinition tool = tool((context, args) -> ToolExecutionResult.success("ok"));
        List<String> warned = new CopyOnWriteArrayList<>();
        try (AgentRunner runner =
                AgentRunner.builder("http://[fd7a:115c:a1e0::e937:334e]:18181", home)
                        .tools(Collections.singletonList(tool))
                        .onStatus(warned::add)
                        .build()) {
            assertNotNull(runner.runnerId());
        }
        assertEquals(1, warned.size());
        assertTrue(warned.get(0).contains("unencrypted"));
        List<String> loopback = new CopyOnWriteArrayList<>();
        try (AgentRunner runner =
                AgentRunner.builder("http://127.0.0.1:18181", home.resolve("loopback"))
                        .tools(Collections.singletonList(tool))
                        .onStatus(loopback::add)
                        .build()) {
            assertNotNull(runner.runnerId());
        }
        assertTrue(loopback.isEmpty());
    }

    @Test
    void oneTerminalUsesEachProjectsWorkspaceAndRejectsDisabledTools() throws Exception {
        AtomicInteger effects = new AtomicInteger();
        ToolDefinition tool =
                tool(
                        (context, args) -> {
                            assertEquals(ToolApprovalMode.FULL_ACCESS, context.getApprovalMode());
                            effects.incrementAndGet();
                            return ToolExecutionResult.success(
                                    new String(
                                            Files.readAllBytes(
                                                    context.getWorkspaceRoot().resolve("name.txt")),
                                            StandardCharsets.UTF_8));
                        });
        try (Server server = new Server();
                AgentRunner runner =
                        AgentRunner.builder(server.url(), home.resolve("state"))
                                .workspaces(new LocalWorkspaces())
                                .tools(Collections.singletonList(tool))
                                .build()) {
            Future<?> receive =
                    server.threads.submit(
                            () -> {
                                runner.serve();
                                return null;
                            });
            for (String name : Arrays.asList("first", "second")) {
                Path directory = Files.createDirectory(home.resolve(name));
                Files.write(directory.resolve("name.txt"), name.getBytes(StandardCharsets.UTF_8));
                server.job = job(runner.runnerId(), tool);
                server.job.request.sessionId = "s_" + name;
                server.job.request.runId = "r_" + name;
                server.job.workspace = directory.toString();
                server.jobs.add(server.job);
                assertEquals(
                        "COMPLETED",
                        Objects.requireNonNull(server.completed.poll(5, TimeUnit.SECONDS)).status);
                assertEquals(
                        name, server.chats.get(server.chats.size() - 1).messages.get(0).content);
            }
            assertTrue(server.registration.get().workspaces);
            server.job = job(runner.runnerId(), tool);
            server.job.workspace = home.toString();
            server.job.request.clientTools.clear();
            server.jobs.add(server.job);
            ChatResponse rejected = server.completed.poll(5, TimeUnit.SECONDS);
            assertNotNull(rejected);
            assertEquals("INTERRUPTED", rejected.status);
            assertTrue(server.chats.get(server.chats.size() - 1).clientTools.isEmpty());
            assertEquals(2, effects.get());
            runner.close();
            receive.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void missingWorkspaceFailsJobWithoutExecutingOrRetryingIt() throws Exception {
        AtomicInteger effects = new AtomicInteger();
        ToolDefinition tool =
                tool(
                        (context, args) -> {
                            effects.incrementAndGet();
                            return ToolExecutionResult.success("ok");
                        });
        try (Server server = new Server();
                AgentRunner runner =
                        AgentRunner.builder(server.url(), home)
                                .workspaces(new LocalWorkspaces())
                                .tools(Collections.singletonList(tool))
                                .build()) {
            server.job = job(runner.runnerId(), tool);
            server.job.workspace = home.resolve("missing").toString();
            server.jobs.add(server.job);
            Future<?> receive =
                    server.threads.submit(
                            () -> {
                                runner.serve();
                                return null;
                            });
            assertEquals(
                    "INTERRUPTED",
                    Objects.requireNonNull(server.completed.poll(5, TimeUnit.SECONDS)).status);
            assertEquals(0, effects.get());
            assertTrue(server.chats.isEmpty());
            runner.close();
            receive.get(5, TimeUnit.SECONDS);
        }
    }

    private RunnerJob job(String id, ToolDefinition tool) {
        RunnerJob job = new RunnerJob();
        job.runnerId = id;
        job.status = "CLAIMED";
        job.request = new ChatRequest();
        job.request.sessionId = "s";
        job.request.runId = "r";
        job.request.stream = true;
        job.request.messages.add(ChatMessage.user("read temperature"));
        job.request.clientTools.add(tool.descriptor());
        return job;
    }

    interface Execute {
        ToolExecutionResult run(ToolContext context, JsonNode args) throws Exception;
    }

    private ToolDefinition tool(Execute execute) {
        return new ToolDefinition() {
            public String getName() {
                return "device_status";
            }

            public String getDescription() {
                return "Read device status";
            }

            public JsonNode getParametersSchema() {
                return json.createObjectNode().put("type", "object");
            }

            public ToolExecutionResult execute(ToolContext context, JsonNode args)
                    throws Exception {
                return execute.run(context, args);
            }
        };
    }

    class Server implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService threads = Executors.newCachedThreadPool();
        final BlockingQueue<RunnerJob> jobs = new LinkedBlockingQueue<>();
        final BlockingQueue<ChatResponse> completed = new LinkedBlockingQueue<>();
        final List<ChatRequest> chats = new CopyOnWriteArrayList<>();
        final AtomicReference<RunnerInfo> registration = new AtomicReference<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        RunnerJob job;

        Server() throws IOException {
            server.setExecutor(threads);
            server.createContext(
                    "/api/v1/",
                    exchange -> {
                        try {
                            String path = exchange.getRequestURI().getPath();
                            Object result = Collections.emptyMap();
                            if (path.equals("/api/v1/runners"))
                                registration.set(
                                        json.readValue(
                                                exchange.getRequestBody(), RunnerInfo.class));
                            else if (path.endsWith("/poll")) {
                                RunnerPoll poll = new RunnerPoll();
                                poll.job = jobs.poll(50, TimeUnit.MILLISECONDS);
                                if (cancelled.get()) poll.cancelledRuns.add("r");
                                result = poll;
                            } else if (path.endsWith("/complete"))
                                completed.add(
                                        json.readValue(
                                                exchange.getRequestBody(), ChatResponse.class));
                            else if (path.endsWith("/state")) result = pending();
                            else if (path.equals("/api/v1/runners/runs/r")) result = job;
                            else if (path.equals("/api/v1/chat")) {
                                ChatRequest request =
                                        json.readValue(
                                                exchange.getRequestBody(), ChatRequest.class);
                                chats.add(request);
                                ChatResponse response = pending();
                                if ("tool".equals(request.messages.get(0).role)) {
                                    response.status = "COMPLETED";
                                    response.toolCalls.clear();
                                    response.answer = "done";
                                }
                                byte[] bytes =
                                        ("event: response\ndata: "
                                                        + json.writeValueAsString(response)
                                                        + "\n\n")
                                                .getBytes(StandardCharsets.UTF_8);
                                exchange.getResponseHeaders()
                                        .add("Content-Type", "text/event-stream");
                                exchange.sendResponseHeaders(200, bytes.length);
                                exchange.getResponseBody().write(bytes);
                                return;
                            }
                            byte[] bytes = json.writeValueAsBytes(result);
                            exchange.sendResponseHeaders(200, bytes.length);
                            exchange.getResponseBody().write(bytes);
                        } catch (Exception e) {
                            throw new IOException(e);
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
        }

        ChatResponse pending() {
            ChatResponse response = new ChatResponse();
            response.sessionId = job == null ? "s" : job.request.sessionId;
            response.runId = job == null ? "r" : job.request.runId;
            response.status = "REQUIRES_ACTION";
            ToolInvocation call = new ToolInvocation();
            call.toolCallId = "r".equals(response.runId) ? "c" : "c_" + response.runId;
            call.name = "device_status";
            call.arguments = new LinkedHashMap<>();
            response.toolCalls.add(call);
            return response;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        public void close() {
            server.stop(0);
            threads.shutdownNow();
        }
    }
}

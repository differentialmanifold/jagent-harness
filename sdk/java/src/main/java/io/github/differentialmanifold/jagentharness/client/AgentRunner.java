package io.github.differentialmanifold.jagentharness.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Reusable local tool host. The application supplies tools and owns the process lifecycle. No
 * workspace, UI, model client or inbound listener is required.
 */
public final class AgentRunner implements AutoCloseable {
    private final Path home;
    private final RunnerFiles.Lease lease;
    private final HttpAgentTransport http;
    private final RunnerInfo info = new RunnerInfo();
    private final List<ToolDefinition> tools = new ArrayList<>();
    private final Object toolContext;
    private final RunnerWorkspaces workspaces;
    private final Consumer<StreamEvent> events;
    private final Consumer<String> status;
    private final Signal closing = new Signal();
    private final AtomicReference<Active> active = new AtomicReference<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean running;
    private boolean released;
    private volatile Thread receiver;
    private volatile Thread executor;

    /** Creates a runner with a persistent identity and local execution journal. */
    public static Builder builder(String server, Path stateDirectory) {
        return new Builder(server, stateDirectory);
    }

    private AgentRunner(Builder b) throws IOException {
        status = b.status;
        String server = serverUrl(b.server);
        if (b.tools.isEmpty()) throw new IllegalArgumentException("Register at least one tool");
        // Validate duplicates before acquiring resources or creating identity files.
        new AgentClient(new HttpAgentTransport(server, b.token), b.tools);
        home = b.home.toAbsolutePath().normalize();
        toolContext = b.toolContext;
        workspaces = b.workspaces;
        events = b.events;
        lease = RunnerFiles.lock(home);
        try {
            Map<String, Object> config = identity(home, server);
            info.runnerId = (String) config.get("runnerId");
            info.name = b.name;
            info.workspaces = workspaces != null;
            info.instructions = b.instructions;
            info.toolDiagnostics.putAll(b.toolDiagnostics);
            http =
                    new HttpAgentTransport(server, b.token)
                            .header("X-Runner-Id", info.runnerId)
                            .header("X-Runner-Secret", (String) config.get("secret"));
            ToolJournal journal = new ToolJournal(home.resolve("results"));
            for (ToolDefinition tool : b.tools) {
                info.tools.add(tool.descriptor());
                tools.add(journal.wrap(tool));
            }
            RunnerFiles.save(home.resolve("config.json"), config);
        } catch (IOException | RuntimeException e) {
            lease.close();
            throw e;
        }
    }

    public String runnerId() {
        return info.runnerId;
    }

    /** Registers tools and receives jobs on the calling thread until close is called. */
    public void serve() throws Exception {
        begin();
        try {
            http.management("POST", "/api/v1/runners", info, closing);
            report("Runner " + info.name + " online: " + info.runnerId);
            int failures = 0;
            while (!closing.isAborted()) {
                try {
                    Active current = active.get();
                    RunnerPoll poll =
                            RunnerFiles.JSON.treeToValue(
                                    http.management(
                                            "GET",
                                            "/api/v1/runners/"
                                                    + info.runnerId
                                                    + "/poll"
                                                    + (current == null
                                                            ? ""
                                                            : "?activeRun="
                                                                    + current.job.request.runId),
                                            null,
                                            closing),
                                    RunnerPoll.class);
                    failures = 0;
                    if (current != null && poll.cancelledRuns.contains(current.job.request.runId)) {
                        current.signal.stop();
                        Thread.sleep(200);
                    }
                    if (poll.workspaceRequest != null) browse(poll.workspaceRequest);
                    if (!closing.isAborted() && poll.job != null && active.get() == null) {
                        Active run = new Active(poll.job);
                        active.set(run);
                        try {
                            worker.submit(
                                    () -> {
                                        executor = Thread.currentThread();
                                        try {
                                            if (closing.isAborted()) run.signal.stop();
                                            execute(run);
                                        } catch (Exception e) {
                                            report("Result delivery pending: " + e.getMessage());
                                        } finally {
                                            executor = null;
                                            active.compareAndSet(run, null);
                                            releaseIfClosed();
                                        }
                                    });
                        } catch (RejectedExecutionException e) {
                            active.compareAndSet(run, null);
                            if (!closing.isAborted()) throw e;
                        }
                    }
                } catch (IOException e) {
                    if (closing.isAborted()) break;
                    if (e instanceof AgentHttpException
                            && (((AgentHttpException) e).status == 401
                                    || ((AgentHttpException) e).status == 403)) throw e;
                    report("Connection interrupted; reconnecting (" + (++failures) + ")");
                    Thread.sleep(Math.min(10000, 500L * (1L << Math.min(failures, 4))));
                }
            }
        } catch (InterruptedException e) {
            if (!closing.isAborted()) {
                Thread.currentThread().interrupt();
                throw e;
            }
        } catch (StopRequestedException e) {
            if (!closing.isAborted()) throw e;
        } finally {
            end();
        }
    }

    /** Explicitly submits saved results; never re-executes a pending tool to recover its result. */
    public ChatResponse recover(String session) throws Exception {
        if (session == null) throw new IllegalArgumentException("Session is required");
        begin();
        try {
            ChatResponse pending = http.state(session);
            if (!"REQUIRES_ACTION".equals(pending.status))
                throw new IllegalArgumentException("Session has no pending tools");
            RunnerJob saved =
                    RunnerFiles.JSON.treeToValue(
                            http.management("GET", "/api/v1/runners/runs/" + pending.runId, null),
                            RunnerJob.class);
            validate(saved);
            ChatRequest request = new ChatRequest();
            request.stream = true;
            request.clientTools = saved.request.clientTools;
            request.clientInstructions = saved.request.clientInstructions;
            request.sessionId = session;
            request.runId = pending.runId;
            ToolJournal journal = new ToolJournal(home.resolve("results"));
            for (ToolInvocation call : pending.toolCalls) {
                ToolExecutionResult result = journal.read(session, call.toolCallId);
                request.messages.add(
                        ChatMessage.tool(call.toolCallId, result.status, result.content));
            }
            http.management(
                    "POST",
                    "/api/v1/runners/" + info.runnerId + "/runs/" + pending.runId + "/recover",
                    Collections.emptyMap());
            Active run = new Active(saved);
            active.set(run);
            if (closing.isAborted()) run.signal.stop();
            ChatResponse response = converse(run, request);
            complete(response);
            return response;
        } finally {
            active.set(null);
            end();
        }
    }

    private void validateTarget(RunnerJob job) {
        if (job.request == null
                || job.request.runId == null
                || !job.request.runId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid run ID");
        if (!info.runnerId.equals(job.runnerId))
            throw new IllegalArgumentException("Use the original runner identity");
    }

    private void validate(RunnerJob job) {
        validateTarget(job);
        Map<String, JsonNode> available = new HashMap<>();
        for (ToolDescriptor tool : info.tools)
            available.put(tool.name, RunnerFiles.JSON.valueToTree(tool));
        for (ToolDescriptor tool : job.request.clientTools)
            if (!RunnerFiles.JSON.valueToTree(tool).equals(available.get(tool.name)))
                throw new IllegalArgumentException(
                        "Use the original tools and execution permissions");
    }

    private void execute(Active run) throws Exception {
        RunnerJob job = run.job;
        validateTarget(job);
        Path file = home.resolve("jobs").resolve(job.request.runId + ".json");
        ChatResponse response;
        if (Files.exists(file)) {
            JsonNode previous = RunnerFiles.JSON.readTree(file.toFile());
            response =
                    previous.has("status")
                            ? RunnerFiles.JSON.treeToValue(previous, ChatResponse.class)
                            : interrupted(
                                    job,
                                    "Runner restarted after accepting this job. Inspect session history and saved tool results; the prompt was not replayed.");
        } else {
            RunnerFiles.save(file, Collections.singletonMap("started", true));
            if (job.cancelled) run.signal.stop();
            response = converse(run, job.request);
        }
        complete(response);
    }

    private ChatResponse converse(Active run, ChatRequest request) {
        try {
            validate(run.job);
            Path workspace = workspaces == null ? null : workspaces.resolve(run.job.workspace);
            Set<String> enabled = new HashSet<>();
            for (ToolDescriptor descriptor : request.clientTools) enabled.add(descriptor.name);
            List<ToolDefinition> selected = new ArrayList<>();
            for (ToolDefinition tool : tools)
                if (enabled.contains(tool.getName())) selected.add(tool);
            return new AgentClient(http, selected)
                    .run(
                            request,
                            new ClientRunOptions()
                                    .toolContext(toolContext)
                                    .workspace(workspace)
                                    .stopSignal(run.signal)
                                    .onEvent(
                                            event -> {
                                                if (events != null) {
                                                    try {
                                                        events.accept(event);
                                                    } catch (RuntimeException e) {
                                                        report(
                                                                "Event listener failed: "
                                                                        + e.getMessage());
                                                    }
                                                }
                                                if (event.eventId != null
                                                        && event.eventId.startsWith("client_")) {
                                                    try {
                                                        http.management(
                                                                "POST",
                                                                "/api/v1/runners/"
                                                                        + info.runnerId
                                                                        + "/events",
                                                                event);
                                                    } catch (IOException e) {
                                                        report(
                                                                "Local progress event could not be delivered");
                                                    }
                                                }
                                            }));
        } catch (Exception e) {
            ChatResponse response = interrupted(run.job, e.toString());
            if (run.signal.isAborted()) response.status = "CANCELLED";
            return response;
        }
    }

    private void browse(WorkspaceRequest request) throws IOException {
        WorkspaceListing result;
        try {
            if (workspaces == null)
                throw new IllegalArgumentException("This runner does not use workspaces");
            result = workspaces.browse(request.path);
        } catch (Exception e) {
            result = new WorkspaceListing();
            result.error = Objects.toString(e.getMessage(), e.getClass().getSimpleName());
        }
        result.requestId = request.requestId;
        http.management(
                "POST", "/api/v1/runners/" + info.runnerId + "/workspaces/result", result, closing);
    }

    private void complete(ChatResponse response) throws IOException {
        RunnerFiles.save(home.resolve("jobs").resolve(response.runId + ".json"), response);
        http.management("POST", "/api/v1/runners/" + info.runnerId + "/complete", response);
        report(response.runId + " " + response.status);
    }

    private static ChatResponse interrupted(RunnerJob job, String reason) {
        ChatResponse response = new ChatResponse();
        response.sessionId = job.request.sessionId;
        response.runId = job.request.runId;
        response.status = "INTERRUPTED";
        response.answer = reason;
        return response;
    }

    private synchronized void begin() {
        if (closing.isAborted() || running || active.get() != null)
            throw new IllegalStateException("Runner is closed or already running");
        running = true;
        receiver = Thread.currentThread();
    }

    private synchronized void end() {
        running = false;
        receiver = null;
        // No receiver remains to deliver cancellation if serve exits with an error.
        Active run = active.get();
        if (run != null) run.signal.stop();
        releaseIfClosed();
    }

    /** Stops polling and signals the active tool. Does not install JVM shutdown hooks. */
    @Override
    public void close() {
        closing.stop();
        Active run = active.get();
        if (run != null) run.signal.stop();
        Thread receiving = receiver;
        if (receiving != null && receiving != Thread.currentThread()) receiving.interrupt();
        Thread executing = executor;
        if (executing != null && executing != Thread.currentThread()) executing.interrupt();
        // Already accepted jobs still record their cancelled/unknown outcomes.
        worker.shutdown();
        releaseIfClosed();
    }

    private synchronized void releaseIfClosed() {
        if (closing.isAborted() && !running && active.get() == null && !released) {
            released = true;
            try {
                lease.close();
            } catch (IOException e) {
                report("Could not release runner state: " + e.getMessage());
            }
        }
    }

    private void report(String message) {
        if (status != null) {
            try {
                status.accept(message);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static Map<String, Object> identity(Path home, String server) throws IOException {
        Path file = home.resolve("config.json");
        if (Files.exists(file)) {
            Map<String, Object> config = RunnerFiles.JSON.readValue(file.toFile(), Map.class);
            if (!server.equals(config.get("server")))
                throw new IllegalArgumentException(
                        "Use another state directory to connect to a different server");
            return config;
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("runnerId", "runner_" + UUID.randomUUID().toString().replace("-", ""));
        config.put("secret", UUID.randomUUID().toString() + UUID.randomUUID());
        config.put("server", server);
        return config;
    }

    private String serverUrl(String server) throws IOException {
        String normalized = Objects.requireNonNull(server).replaceAll("/$", "");
        URL url = new URL(normalized);
        if (url.getUserInfo() != null
                || url.getQuery() != null
                || url.getRef() != null
                || !("".equals(url.getPath()) || "/".equals(url.getPath()))
                || !Arrays.asList("http", "https").contains(url.getProtocol()))
            throw new IllegalArgumentException("Use a plain http(s) server origin");
        if ("http".equals(url.getProtocol())
                && !Arrays.asList("localhost", "127.0.0.1", "[::1]").contains(url.getHost()))
            report(
                    "Plain HTTP to a non-loopback server; the token and job traffic are sent unencrypted");
        return normalized;
    }

    public static final class Builder {
        private final String server;
        private final Path home;
        private String token = "";
        private String name = "Java runner";
        private RunnerWorkspaces workspaces;
        private String instructions = "";
        private final List<ToolDefinition> tools = new ArrayList<>();
        private final Map<String, String> toolDiagnostics = new LinkedHashMap<>();
        private Object toolContext;
        private Consumer<StreamEvent> events;
        private Consumer<String> status;

        private Builder(String server, Path home) {
            this.server = server;
            this.home = Objects.requireNonNull(home);
        }

        public Builder token(String token) {
            this.token = token;
            return this;
        }

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name);
            return this;
        }
        /** Enables per-project directories. Omit for tools without a filesystem workspace. */
        public Builder workspaces(RunnerWorkspaces workspaces) {
            this.workspaces = Objects.requireNonNull(workspaces);
            return this;
        }

        public Builder instructions(String instructions) {
            this.instructions = Objects.requireNonNull(instructions);
            return this;
        }

        public Builder tools(Collection<? extends ToolDefinition> tools) {
            this.tools.addAll(tools);
            return this;
        }
        /**
         * Local application state, such as a device API. Never serialized or sent to the server.
         */
        public Builder toolContext(Object context) {
            this.toolContext = context;
            return this;
        }

        /** Startup diagnostics shown beside tools in the console, separate from model schemas. */
        public Builder toolDiagnostics(Map<String, String> diagnostics) {
            this.toolDiagnostics.putAll(diagnostics);
            return this;
        }

        public Builder onEvent(Consumer<StreamEvent> events) {
            this.events = events;
            return this;
        }

        public Builder onStatus(Consumer<String> status) {
            this.status = status;
            return this;
        }

        public AgentRunner build() throws IOException {
            return new AgentRunner(this);
        }
    }

    private static final class Active {
        final RunnerJob job;
        final Signal signal = new Signal();

        Active(RunnerJob job) {
            this.job = job;
        }
    }

    static final class Signal implements StopSignal {
        private boolean stopped;
        private final List<Runnable> listeners = new ArrayList<>();

        public synchronized boolean isAborted() {
            return stopped;
        }

        public void throwIfAborted() {
            if (isAborted()) throw new StopRequestedException();
        }

        public StopRegistration onStop(Runnable action) {
            synchronized (this) {
                if (!stopped) {
                    listeners.add(action);
                    return () -> remove(action);
                }
            }
            action.run();
            return () -> {};
        }

        private synchronized void remove(Runnable action) {
            listeners.remove(action);
        }

        void stop() {
            List<Runnable> pending;
            synchronized (this) {
                if (stopped) return;
                stopped = true;
                pending = new ArrayList<>(listeners);
                listeners.clear();
            }
            for (Runnable action : pending) {
                try {
                    action.run();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}

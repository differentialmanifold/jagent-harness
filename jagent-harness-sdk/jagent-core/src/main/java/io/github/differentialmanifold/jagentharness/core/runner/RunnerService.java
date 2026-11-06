package io.github.differentialmanifold.jagentharness.core.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.ClientCapabilities;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.execution.ConversationService;
import io.github.differentialmanifold.jagentharness.core.execution.ProtocolException;
import io.github.differentialmanifold.jagentharness.core.execution.ProtocolMapper;
import io.github.differentialmanifold.jagentharness.core.execution.RunHandle;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Dispatch is separate from the ordinary conversation protocol. No server-to-device connections.
 */
public class RunnerService implements AutoCloseable {
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(RunnerService.class.getName());
    private boolean databaseFailure;
    private final ExecutorService workers =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r, "runner-conversation");
                        t.setDaemon(true);
                        return t;
                    });
    private final RunnerStore store;
    private final SessionManager sessions;
    private final ConversationService chat;
    private final AgentEventPublisher publisher;
    private final ObjectMapper json;
    private final RunnerWorkspaceRequests workspaces = new RunnerWorkspaceRequests();
    private final ConcurrentMap<String, Waiter> waiters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CopyOnWriteArrayList<RunHandle>> observers =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "runner-dispatch");
                        t.setDaemon(true);
                        return t;
                    });

    public RunnerService(
            RunnerStore store,
            SessionManager sessions,
            ConversationService chat,
            AgentEventPublisher publisher,
            ObjectMapper json) {
        this.store = store;
        this.sessions = sessions;
        this.chat = chat;
        this.publisher = publisher;
        this.json = json;
        timer.scheduleWithFixedDelay(this::tick, 200, 200, TimeUnit.MILLISECONDS);
    }

    public static String hash(String value) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public void authenticate(String id, String secret) {
        if (id == null || secret == null)
            throw new ProtocolException(403, "RUNNER_AUTH", "Runner credentials are required");
        String expected = store.credentialHash(id);
        if (secret == null
                || expected == null
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        hash(secret).getBytes(StandardCharsets.UTF_8)))
            throw new ProtocolException(403, "RUNNER_AUTH", "Invalid runner credentials");
    }

    public synchronized RunnerInfo register(RunnerInfo info, String secret) {
        if (info == null
                || info.runnerId == null
                || !info.runnerId.matches("[A-Za-z0-9_-]{1,128}")
                || secret == null
                || secret.length() < 32
                || secret.length() > 256
                || info.name == null
                || info.name.length() > 128
                || info.tools == null
                || info.tools.isEmpty()
                || info.tools.size() > 128
                || info.instructions == null
                || info.instructions.length() > 32000
                || (info.workspaceRoot != null && info.workspaceRoot.length() > 2048))
            throw new ProtocolException(
                    400, "INVALID_RUNNER", "Runner identity and tools are required");
        Set<String> names = new HashSet<>();
        for (ToolDescriptor t : info.tools)
            if (t == null
                    || t.name == null
                    || !t.name.matches("[A-Za-z0-9_-]{1,64}")
                    || !names.add(t.name)
                    || t.parameters == null
                    || !"object".equals(t.parameters.get("type")))
                throw new ProtocolException(400, "INVALID_TOOLS", "Invalid runner tool catalog");
        RunnerInfo previous = store.runner(info.runnerId);
        if (previous != null) {
            authenticate(info.runnerId, secret);
            if (!store.unfinished(info.runnerId).isEmpty()
                    && (!json.valueToTree(previous.tools).equals(json.valueToTree(info.tools))
                            || !Objects.equals(previous.instructions, info.instructions)
                            || previous.workspaces != info.workspaces
                            || !Objects.equals(previous.workspaceRoot, info.workspaceRoot)))
                throw new ProtocolException(
                        409, "RUNNER_BUSY", "Finish pending jobs before changing tools");
        }
        store.register(info, hash(secret));
        // Re-registration starts a new receiver after reconnect or process restart.
        Waiter previousPoll = waiters.remove(info.runnerId);
        if (previousPoll != null) previousPoll.result.complete(new RunnerPoll());
        return info;
    }

    public List<RunnerInfo> list() {
        List<RunnerInfo> runners = store.runners();
        for (RunnerInfo info : runners)
            info.online = System.currentTimeMillis() - info.lastSeen < 65000;
        return runners;
    }

    public RunnerInfo require(String id) {
        RunnerInfo info = store.runner(id);
        if (info == null) throw new ProtocolException(404, "RUNNER_NOT_FOUND", "Runner not found");
        return info;
    }

    public String binding(String session) {
        return store.runnerForSession(session);
    }

    public CompletableFuture<WorkspaceListing> browse(String runner, String path) {
        RunnerInfo info = require(runner);
        if (!info.workspaces)
            throw new ProtocolException(400, "NO_WORKSPACES", "Runner does not use workspaces");
        if (System.currentTimeMillis() - info.lastSeen > 65000)
            throw new ProtocolException(409, "RUNNER_OFFLINE", "Runner is offline");
        CompletableFuture<WorkspaceListing> result = workspaces.browse(runner, path);
        wake(runner);
        return result;
    }

    public void workspaceResult(String runner, WorkspaceListing listing) {
        workspaces.complete(runner, listing);
    }

    public void bind(String session, String runner) {
        require(runner);
        store.bind(session, runner);
    }

    public RunnerProject project(String id) {
        return id == null || id.isEmpty() ? null : store.project(id);
    }

    public ClientCapabilities capabilities(SessionRecord session) {
        RunnerProject project = project(session.getProjectId());
        if (project == null || !project.runnerId.equals(binding(session.getSessionId())))
            throw new ProtocolException(
                    409,
                    "PROJECT_REQUIRED",
                    "Project execution settings do not match this session");
        RunnerInfo info = require(project.runnerId);
        return new ClientCapabilities(enabledTools(project, info), info.instructions);
    }

    private List<ToolDescriptor> enabledTools(RunnerProject project, RunnerInfo info) {
        List<ToolDescriptor> selected = new ArrayList<>();
        for (ToolDescriptor tool : info.tools)
            if (project.enabledTools.contains(tool.name)) selected.add(tool);
        return selected;
    }

    public synchronized SessionRecord createSession(
            io.github.differentialmanifold.jagentharness.core.project.CreateSessionRequest request,
            RunnerProject existing) {
        return createSession(request, existing, null);
    }

    public synchronized SessionRecord createSession(
            io.github.differentialmanifold.jagentharness.core.project.CreateSessionRequest request,
            RunnerProject existing,
            String ownerAgentId) {
        RunnerProject project = existing;
        if (project == null) {
            RunnerInfo info = require(request.getRunnerId());
            project = new RunnerProject();
            project.runnerId = info.runnerId;
            project.workspace = info.workspaces ? request.getWorkspacePath() : null;
            if (info.workspaces
                    && (project.workspace == null || project.workspace.trim().isEmpty())
                    && info.workspaceRoot != null
                    && !info.workspaceRoot.trim().isEmpty()) {
                if (ownerAgentId != null && !ownerAgentId.matches("agent_[A-Za-z0-9_-]+"))
                    throw new IllegalArgumentException("Invalid Agent ID");
                String projectId =
                        io.github.differentialmanifold.jagentharness.core.support.Ids.newId(
                                "project");
                request.setProjectId(projectId);
                project.workspace =
                        info.workspaceRoot.replace('\\', '/').replaceAll("/+$", "")
                                + (ownerAgentId == null
                                        ? "/user/"
                                        : "/agents/" + ownerAgentId + "/")
                                + projectId;
            }
            if (info.workspaces
                    && (project.workspace == null
                            || project.workspace.trim().isEmpty()
                            || project.workspace.length() > 2048))
                throw new ProtocolException(
                        400, "WORKSPACE_REQUIRED", "Select a workspace on the runner");
            project.enabledTools = selectedTools(info, request.getEnabledTools());
        } else if (request.getRunnerId() != null
                && !request.getRunnerId().isEmpty()
                && !project.runnerId.equals(request.getRunnerId())) {
            throw new ProtocolException(409, "PROJECT_RUNNER", "Project belongs to another runner");
        }
        SessionRecord session =
                sessions.createSession(
                        request.getTitle(),
                        project.workspace,
                        request.getProjectName(),
                        request.getProjectId(),
                        ownerAgentId);
        if (existing == null) {
            project.projectId = session.getProjectId();
            store.saveProject(project);
        }
        bind(session.getSessionId(), project.runnerId);
        return session;
    }

    private List<String> selectedTools(RunnerInfo info, List<String> selection) {
        Set<String> available = new LinkedHashSet<>();
        for (ToolDescriptor tool : info.tools) available.add(tool.name);
        if (selection == null) return new ArrayList<>(available);
        if (selection.contains(null) || !available.containsAll(selection))
            throw new ProtocolException(
                    400, "INVALID_TOOLS", "Select tools registered by this runner");
        return new ArrayList<>(new LinkedHashSet<>(selection));
    }

    public synchronized RunnerProject selectTools(String projectId, List<String> names) {
        RunnerProject project = project(projectId);
        if (project == null)
            throw new ProtocolException(404, "PROJECT_NOT_FOUND", "Runner project not found");
        if (names == null)
            throw new ProtocolException(400, "INVALID_TOOLS", "enabledTools is required");
        project.enabledTools = selectedTools(require(project.runnerId), names);
        store.saveProject(project);
        return project;
    }

    public RunHandle submit(ChatRequest request) {
        return submit(request, null);
    }

    public RunHandle submit(ChatRequest request, Consumer<StreamEvent> listener) {
        String runner = queue(request);
        RunHandle handle = watch(request.runId, listener);
        wake(runner);
        return handle;
    }

    private String queue(ChatRequest request) {
        if (request == null
                || request.sessionId == null
                || request.runId == null
                || !request.runId.matches("[A-Za-z0-9_-]{1,64}")
                || request.messages == null
                || request.messages.size() != 1
                || request.messages.get(0) == null
                || !"user".equals(request.messages.get(0).role)
                || request.messages.get(0).content == null)
            throw new ProtocolException(
                    400, "INVALID_REQUEST", "Submit one user message with sessionId and runId");
        SessionRecord session = sessions.requireSession(request.sessionId);
        String runner = binding(request.sessionId);
        if (runner == null)
            throw new ProtocolException(400, "NO_RUNNER", "Session has no remote runner");
        RunnerInfo info = require(runner);
        if (System.currentTimeMillis() - info.lastSeen > 65000)
            throw new ProtocolException(409, "RUNNER_OFFLINE", "Runner is offline");
        try {
            if ("REQUIRES_ACTION".equals(chat.state(request.sessionId).status))
                throw new ProtocolException(
                        409,
                        "PENDING_TOOLS",
                        "Resolve pending tool results before starting another turn");
        } catch (ProtocolException e) {
            if (e.status != 404) throw e;
        }
        RunnerJob job = new RunnerJob();
        job.runnerId = runner;
        RunnerProject project = project(session.getProjectId());
        if (project == null)
            throw new ProtocolException(
                    409, "PROJECT_REQUIRED", "Create a project with an execution target and tools");
        job.workspace = project.workspace;
        job.request = request;
        request.clientTools = enabledTools(project, info);
        request.clientInstructions = info.instructions;
        request.stream = true;
        try {
            store.submit(job);
        } catch (RuntimeException e) {
            throw new ProtocolException(409, "RUNNER_BUSY", e.getMessage());
        }
        return runner;
    }

    /** Observing a run never starts or replays it. Closing an observer does not cancel work. */
    public RunHandle watch(String run, Consumer<StreamEvent> listener) {
        RunnerJob job = job(run);
        RunHandle handle = new RunHandle(job.request.sessionId, run, listener);
        observers.compute(
                run,
                (id, list) -> {
                    if (list == null) list = new CopyOnWriteArrayList<>();
                    list.add(handle);
                    return list;
                });
        handle.onClose(
                () ->
                        observers.computeIfPresent(
                                run,
                                (id, list) -> {
                                    list.remove(handle);
                                    return list.isEmpty() ? null : list;
                                }));
        // Re-read after subscribing to cover completion concurrent with subscription.
        ChatResponse response = job(run).response;
        if (response != null) handle.complete(response);
        return handle;
    }

    public List<RunnerJob> unfinished(String runner) {
        return store.unfinished(runner);
    }

    public RunnerJob job(String run) {
        RunnerJob job = store.job(run);
        if (job == null) throw new ProtocolException(404, "NOT_FOUND", "Runner job not found");
        return job;
    }

    public RunnerJob recover(String runner, String run) {
        RunnerJob job = job(run);
        if (!runner.equals(job.runnerId))
            throw new ProtocolException(403, "WRONG_RUNNER", "Job belongs to another runner");
        if (!"REQUIRES_ACTION".equals(chat.state(job.request.sessionId).status))
            throw new ProtocolException(
                    409, "NO_PENDING_TOOLS", "No pending tool results to recover");
        try {
            store.reopen(run);
        } catch (IllegalStateException e) {
            throw new ProtocolException(409, "RECOVERY_CONFLICT", e.getMessage());
        }
        return store.job(run);
    }

    /** Internal Runner conversation request, never a new dispatch submission. */
    public RunHandle chat(
            ChatRequest request, String runner, String secret, Consumer<StreamEvent> listener) {
        authorizeChat(request, runner, secret);
        RunHandle handle = new RunHandle(request.sessionId, request.runId, listener);
        try {
            workers.execute(
                    () -> {
                        try {
                            handle.complete(
                                    chat.chat(
                                            request,
                                            event -> {
                                                event(event);
                                                handle.emit(event);
                                            }));
                        } catch (Throwable error) {
                            handle.fail(error);
                        }
                    });
        } catch (RejectedExecutionException error) {
            throw new ProtocolException(503, "SERVER_STOPPING", "Server is stopping");
        }
        return handle;
    }

    public void authorizeChat(ChatRequest request, String runner, String secret) {
        authenticate(runner, secret);
        chat.validate(request);
        String bound = binding(request.sessionId);
        if (bound == null)
            throw new ProtocolException(403, "WRONG_RUNNER", "Session has no assigned Runner");
        RunnerJob job = job(request.runId);
        if (!bound.equals(runner)
                || !runner.equals(job.runnerId)
                || !request.sessionId.equals(job.request.sessionId)
                || !"CLAIMED".equals(job.status))
            throw new ProtocolException(
                    409, "WRONG_RUNNER", "Conversation is not assigned to this runner");
        if (job.cancelled && !"tool".equals(request.messages.get(0).role))
            throw new ProtocolException(409, "CANCELLED", "Runner job was cancelled");
        // Freeze the advertised catalog to the version accepted for this job.
        request.clientTools = job.request.clientTools;
        request.clientInstructions = job.request.clientInstructions;
    }

    public CompletableFuture<RunnerPoll> poll(String runner, String activeRun) {
        return poll(runner, activeRun, activeRun == null || activeRun.isEmpty() ? 1 : 0);
    }

    public CompletableFuture<RunnerPoll> poll(
            String runner, String activeRuns, int availableSlots) {
        if (availableSlots < 0)
            throw new ProtocolException(
                    400, "INVALID_CAPACITY", "availableSlots must not be negative");
        store.heartbeat(runner, System.currentTimeMillis());
        CompletableFuture<RunnerPoll> result = new CompletableFuture<>();
        Waiter waiter = new Waiter(runner, activeRuns, availableSlots > 0, result);
        Waiter previous = waiters.putIfAbsent(runner, waiter);
        if (previous != null)
            throw new ProtocolException(
                    409, "RUNNER_CONNECTED", "Only one poll per runner is allowed");
        ScheduledFuture<?> timeout =
                timer.schedule(() -> result.complete(new RunnerPoll()), 25, TimeUnit.SECONDS);
        result.whenComplete(
                (value, error) -> {
                    waiters.remove(runner, waiter);
                    timeout.cancel(false);
                });
        wake(runner);
        return result;
    }

    private void wake(String runner) {
        Waiter waiter = waiters.get(runner);
        if (waiter == null) return;
        synchronized (waiter) {
            if (waiter.result.isDone()) return;
            RunnerPoll result = new RunnerPoll();
            result.workspaceRequest = workspaces.poll(runner);
            for (String run : waiter.active) {
                RunnerJob active = store.job(run);
                if (active != null
                        && runner.equals(active.runnerId)
                        && (active.cancelled
                                || !Arrays.asList("QUEUED", "CLAIMED").contains(active.status)))
                    result.cancelledRuns.add(run);
            }
            if (waiter.acceptJobs) {
                for (RunnerJob job : store.unfinished(runner)) {
                    if (waiter.active.contains(job.request.runId)) continue;
                    if (job.cancelled && "QUEUED".equals(job.status)) continue;
                    if ("CLAIMED".equals(job.status) || store.claim(job.request.runId)) {
                        job.status = "CLAIMED";
                        result.job = job;
                        break;
                    }
                }
            }
            if (result.job != null
                    || result.workspaceRequest != null
                    || !result.cancelledRuns.isEmpty()) {
                waiters.remove(runner, waiter);
                waiter.result.complete(result);
            }
        }
    }

    public boolean cancel(String run) {
        RunnerJob job = store.job(run);
        if (job == null) return false;
        store.cancel(run);
        if ("QUEUED".equals(job.status)) {
            ChatResponse response = new ChatResponse();
            response.sessionId = job.request.sessionId;
            response.runId = run;
            response.status = "CANCELLED";
            store.complete(run, response);
        }
        wake(job.runnerId);
        return true;
    }

    public void complete(String runner, ChatResponse response) {
        if (response == null
                || !Arrays.asList("COMPLETED", "CANCELLED", "INTERRUPTED")
                        .contains(response.status))
            throw new ProtocolException(400, "INVALID_RESULT", "A terminal response is required");
        RunnerJob job = job(response.runId);
        if (!runner.equals(job.runnerId)
                || !Objects.equals(response.sessionId, job.request.sessionId))
            throw new ProtocolException(403, "WRONG_RUNNER", "Job belongs to another runner");
        store.complete(response.runId, response);
        finish(response.runId, response);
    }

    public void event(StreamEvent event) {
        List<RunHandle> listeners = observers.get(event.runId);
        if (listeners != null) for (RunHandle handle : listeners) handle.emit(event);
    }

    public void localEvent(String runner, StreamEvent event) {
        RunnerJob job = job(event.runId);
        if (!runner.equals(job.runnerId)
                || !Objects.equals(job.request.sessionId, event.sessionId)
                || !"CLAIMED".equals(job.status)
                || !Arrays.asList("tool_execution_start", "tool_execution_end")
                        .contains(event.type))
            throw new ProtocolException(400, "INVALID_EVENT", "Invalid local tool event");
        event(
                ProtocolMapper.event(
                        publisher.publish(
                                event.sessionId,
                                event.runId,
                                event.turnId,
                                event.type,
                                event.payload),
                        json));
    }

    private void finish(String run, ChatResponse response) {
        List<RunHandle> listeners = observers.remove(run);
        if (listeners != null) for (RunHandle handle : listeners) handle.complete(response);
    }

    private void tick() {
        try {
            for (String runner : waiters.keySet()) wake(runner);
            for (String run : observers.keySet()) {
                RunnerJob job = store.job(run);
                if (job != null && job.response != null) finish(run, job.response);
            }
            workspaces.expire();
            databaseFailure = false;
        } catch (Exception e) {
            if (!databaseFailure)
                LOG.log(
                        java.util.logging.Level.WARNING,
                        "Runner dispatch database check failed; durable jobs will be checked again",
                        e);
            databaseFailure = true;
        }
    }

    public void close() {
        timer.shutdownNow();
        workers.shutdownNow();
        workspaces.close();
        for (Waiter w : waiters.values()) w.result.complete(new RunnerPoll());
        for (List<RunHandle> handles : observers.values())
            for (RunHandle handle : handles)
                handle.fail(new ProtocolException(503, "SERVER_STOPPING", "Server is stopping"));
        observers.clear();
    }

    private static class Waiter {
        final String runner;
        final Set<String> active;
        final boolean acceptJobs;
        final CompletableFuture<RunnerPoll> result;

        Waiter(String r, String a, boolean acceptJobs, CompletableFuture<RunnerPoll> d) {
            runner = r;
            active =
                    a == null || a.isEmpty()
                            ? Collections.emptySet()
                            : new HashSet<>(Arrays.asList(a.split(",")));
            this.acceptJobs = acceptJobs;
            result = d;
        }
    }
}

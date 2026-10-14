package io.github.differentialmanifold.jagentharness.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.ClientCapabilities;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerStore;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Dispatch is separate from the ordinary conversation protocol. No server-to-device connections.
 */
public class RunnerService {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RunnerService.class);
    private long lastHeartbeat;
    private boolean databaseFailure;
    private final RunnerStore store;
    private final SessionManager sessions;
    private final ChatService chat;
    private final AgentEventPublisher publisher;
    private final ObjectMapper json;
    private final RunnerWorkspaceRequests workspaces = new RunnerWorkspaceRequests();
    private final ConcurrentMap<String, Waiter> waiters = new ConcurrentHashMap<>();
    private final Set<String> started = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, SseEmitter> streams = new ConcurrentHashMap<>();
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
            ChatService chat,
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
                || info.instructions.length() > 32000)
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
                            || previous.workspaces != info.workspaces))
                throw new ProtocolException(
                        409, "RUNNER_BUSY", "Finish pending jobs before changing tools");
        }
        store.register(info, hash(secret));
        // Re-registration starts a new receiver after reconnect or process restart.
        Waiter previousPoll = waiters.remove(info.runnerId);
        if (previousPoll != null) previousPoll.result.setResult(new RunnerPoll());
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

    public DeferredResult<WorkspaceListing> browse(String runner, String path) {
        RunnerInfo info = require(runner);
        if (!info.workspaces)
            throw new ProtocolException(400, "NO_WORKSPACES", "Runner does not use workspaces");
        if (System.currentTimeMillis() - info.lastSeen > 65000)
            throw new ProtocolException(409, "RUNNER_OFFLINE", "Runner is offline");
        DeferredResult<WorkspaceListing> result = workspaces.browse(runner, path);
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
            io.github.differentialmanifold.jagentharness.spring.web.dto.CreateSessionRequest
                    request,
            RunnerProject existing) {
        RunnerProject project = existing;
        if (project == null) {
            RunnerInfo info = require(request.getRunnerId());
            project = new RunnerProject();
            project.runnerId = info.runnerId;
            project.workspace = info.workspaces ? request.getWorkspacePath() : null;
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
                        request.getProjectId());
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

    public SseEmitter submit(ChatRequest request) {
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
        SseEmitter emitter = watch(request.runId);
        wake(runner);
        return emitter;
    }

    public SseEmitter watch(String run) {
        started.remove(run);
        SseEmitter emitter = new SseEmitter(0L);
        SseEmitter old = streams.put(run, emitter);
        if (old != null) old.complete();
        emitter.onCompletion(
                () -> {
                    if (streams.remove(run, emitter)) started.remove(run);
                });
        emitter.onError(e -> streams.remove(run, emitter));
        return emitter;
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

    public void authorizeChat(ChatRequest request, String runner, String secret) {
        String bound = binding(request.sessionId);
        if (bound == null) return;
        authenticate(runner, secret);
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

    public DeferredResult<RunnerPoll> poll(String runner, String activeRun) {
        store.heartbeat(runner, System.currentTimeMillis());
        DeferredResult<RunnerPoll> result = new DeferredResult<>(25000L, new RunnerPoll());
        Waiter waiter = new Waiter(runner, activeRun, result);
        Waiter previous = waiters.putIfAbsent(runner, waiter);
        if (previous != null)
            throw new ProtocolException(
                    409, "RUNNER_CONNECTED", "Only one poll per runner is allowed");
        result.onCompletion(() -> waiters.remove(runner, waiter));
        result.onTimeout(() -> waiters.remove(runner, waiter));
        result.onError(error -> waiters.remove(runner, waiter));
        wake(runner);
        return result;
    }

    private void wake(String runner) {
        Waiter waiter = waiters.get(runner);
        if (waiter == null) return;
        synchronized (waiter) {
            if (waiter.result.isSetOrExpired()) return;
            RunnerPoll result = new RunnerPoll();
            result.workspaceRequest = workspaces.poll(runner);
            if (waiter.active != null && !waiter.active.isEmpty()) {
                RunnerJob active = store.job(waiter.active);
                if (active != null
                        && runner.equals(active.runnerId)
                        && (active.cancelled
                                || !Arrays.asList("QUEUED", "CLAIMED").contains(active.status)))
                    result.cancelledRuns.add(waiter.active);
            } else {
                for (RunnerJob job : store.unfinished(runner)) {
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
                waiter.result.setResult(result);
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
        SseEmitter emitter = streams.get(event.runId);
        if (emitter != null && (!"agent_start".equals(event.type) || started.add(event.runId)))
            send(emitter, event.type, event);
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
        started.remove(run);
        SseEmitter emitter = streams.remove(run);
        if (emitter != null) {
            send(emitter, "response", response);
            emitter.complete();
        }
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastHeartbeat >= 15000) {
                lastHeartbeat = now;
                for (SseEmitter emitter : streams.values())
                    try {
                        emitter.send(SseEmitter.event().comment("keepalive"));
                    } catch (Exception e) {
                        emitter.complete();
                    }
            }
            for (String runner : waiters.keySet()) wake(runner);
            for (Map.Entry<String, SseEmitter> entry : streams.entrySet()) {
                RunnerJob job = store.job(entry.getKey());
                if (job != null && job.response != null) finish(entry.getKey(), job.response);
            }
            databaseFailure = false;
        } catch (Exception e) {
            if (!databaseFailure)
                LOG.warn(
                        "Runner dispatch database check failed; durable jobs will be checked again",
                        e);
            databaseFailure = true;
        }
    }

    private void send(SseEmitter emitter, String type, Object value) {
        try {
            emitter.send(SseEmitter.event().name(type).data(value));
        } catch (Exception e) {
            emitter.complete();
        }
    }

    @PreDestroy
    public void close() {
        timer.shutdownNow();
        workspaces.close();
        for (Waiter w : waiters.values()) w.result.setResult(new RunnerPoll());
        for (SseEmitter s : streams.values()) s.complete();
    }

    private static class Waiter {
        final String runner, active;
        final DeferredResult<RunnerPoll> result;

        Waiter(String r, String a, DeferredResult<RunnerPoll> d) {
            runner = r;
            active = a;
            result = d;
        }
    }
}

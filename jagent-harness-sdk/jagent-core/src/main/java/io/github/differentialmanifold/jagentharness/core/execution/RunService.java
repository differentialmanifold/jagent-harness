package io.github.differentialmanifold.jagentharness.core.execution;

import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Public conversation submission and execution control, independent of the caller and HTTP. */
public class RunService implements AutoCloseable {
    private final ConversationService conversations;
    private final RunnerService runners;
    private final SessionManager sessions;
    private final RunInputCoordinator inputs;
    private final ConcurrentMap<String, LocalRun> local = new ConcurrentHashMap<>();
    private final ExecutorService workers =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread thread = new Thread(r, "project-run");
                        thread.setDaemon(true);
                        return thread;
                    });

    public RunService(
            ConversationService conversations,
            RunnerService runners,
            SessionManager sessions,
            RunInputCoordinator inputs) {
        this.conversations = conversations;
        this.runners = runners;
        this.sessions = sessions;
        this.inputs = inputs;
    }

    public RunHandle submit(ChatRequest request, Consumer<StreamEvent> events) {
        conversations.validate(request);
        sessions.requireSession(request.sessionId);
        if (request.runId == null)
            request.runId =
                    "tool".equals(request.messages.get(0).role)
                            ? conversations.state(request.sessionId).runId
                            : Ids.newId("run");
        if (runners != null && runners.binding(request.sessionId) != null)
            return runners.submit(request, events);
        RunHandle handle = new RunHandle(request.sessionId, request.runId, events);
        LocalRun pending = new LocalRun(handle);
        if (local.putIfAbsent(request.sessionId, pending) != null)
            throw new ProtocolException(
                    409, "SESSION_BUSY", "Another request is modifying this session");
        try {
            workers.execute(
                    () -> {
                        try {
                            ChatResponse result =
                                    conversations.chat(request, handle::emit, pending.stop);
                            local.remove(request.sessionId, pending);
                            handle.complete(result);
                        } catch (Throwable error) {
                            local.remove(request.sessionId, pending);
                            handle.fail(error);
                        }
                    });
        } catch (RejectedExecutionException error) {
            local.remove(request.sessionId, pending);
            throw new ProtocolException(503, "UNAVAILABLE", "Agent server is shutting down");
        }
        return handle;
    }

    public ChatResponse state(String sessionId) {
        sessions.requireSession(sessionId);
        LocalRun active = local.get(sessionId);
        if (active != null) return running(sessionId, active.handle.runId);
        String runner = runners == null ? null : runners.binding(sessionId);
        if (runner != null)
            for (RunnerJob job : runners.unfinished(runner))
                if (sessionId.equals(job.request.sessionId)) {
                    try {
                        ChatResponse state = conversations.state(sessionId);
                        if (job.request.runId.equals(state.runId)) return state;
                    } catch (ProtocolException missing) {
                        if (missing.status != 404) throw missing;
                    }
                    return running(sessionId, job.request.runId);
                }
        return conversations.state(sessionId);
    }

    /** Null means no execution can be established; it never grants permission to replay work. */
    public ChatResponse execution(String sessionId, String runId) {
        if (runners != null && runners.binding(sessionId) != null) {
            try {
                RunnerJob job = runners.job(runId);
                if (!sessionId.equals(job.request.sessionId))
                    throw new IllegalArgumentException("Run belongs to another session");
                return job.response == null ? running(sessionId, runId) : job.response;
            } catch (ProtocolException e) {
                if (e.status == 404) return null;
                throw e;
            }
        }
        try {
            ChatResponse response = state(sessionId);
            return runId.equals(response.runId) ? response : null;
        } catch (ProtocolException e) {
            if (e.status == 404) return null;
            throw e;
        }
    }

    public void requireRun(String sessionId, String runId) {
        ChatResponse response = execution(sessionId, runId);
        if (response == null
                || !Arrays.asList("RUNNING", "REQUIRES_ACTION").contains(response.status))
            throw new ProtocolException(
                    409, "RUN_NOT_ACTIVE", "Run is no longer active in this session");
    }

    public RunInputReceipt input(
            String runId, String text, String inputId, List<MessageImage> images) {
        return inputs.submitInput(runId, text, images, inputId);
    }

    public void stop(String runId) {
        boolean localStopped = false;
        for (LocalRun active : local.values())
            if (runId.equals(active.handle.runId)) {
                active.stop.stop();
                localStopped = true;
            }
        boolean remote = runners != null && runners.cancel(runId);
        try {
            conversations.cancel(runId);
        } catch (ProtocolException e) {
            if ((!remote && !localStopped) || e.status != 404) throw e;
        }
    }

    private ChatResponse running(String sessionId, String runId) {
        ChatResponse response = new ChatResponse();
        response.sessionId = sessionId;
        response.runId = runId;
        response.status = "RUNNING";
        return response;
    }

    public void close() {
        for (LocalRun active : local.values()) active.stop.stop();
        workers.shutdownNow();
    }

    private static final class LocalRun {
        final RunHandle handle;
        final RunCancellation stop = new RunCancellation();

        LocalRun(RunHandle handle) {
            this.handle = handle;
        }
    }
}

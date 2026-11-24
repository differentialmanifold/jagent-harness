package io.github.differentialmanifold.jagentharness.core.execution;

import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.identity.*;
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
    private final UserState<ConcurrentMap<String, LocalRun>> local =
            new UserState<>(() -> new ConcurrentHashMap<>());
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

    public RunHandle submit(ChatSubmission submission, Consumer<StreamEvent> events) {
        if (submission == null
                || submission.messages == null
                || submission.messages.size() != 1
                || submission.messages.get(0) == null
                || !"user".equals(submission.messages.get(0).role)
                || submission.messages.get(0).toolCallId != null
                || submission.messages.get(0).status != null)
            throw new ProtocolException(
                    400,
                    "INVALID_MESSAGES",
                    "Submit exactly one user message; tool results require the Runner protocol");
        ChatRequest request = new ChatRequest();
        request.modelId = submission.modelId;
        request.sessionId = submission.sessionId;
        request.runId = submission.runId;
        request.messages = submission.messages;
        request.stream = submission.stream;
        conversations.validate(request);
        sessions.requireSession(request.sessionId);
        if (request.runId == null) request.runId = Ids.newId("run");
        if (runners != null && runners.binding(request.sessionId) != null)
            return runners.submit(request, events);
        RunHandle handle = new RunHandle(request.sessionId, request.runId, events);
        LocalRun pending = new LocalRun(handle);
        if (local.get().putIfAbsent(request.sessionId, pending) != null)
            throw new ProtocolException(
                    409, "SESSION_BUSY", "Another request is modifying this session");
        try {
            workers.execute(
                    UserContext.capture(
                            () -> {
                                try {
                                    ChatResponse result =
                                            conversations.chat(request, handle::emit, pending.stop);
                                    local.get().remove(request.sessionId, pending);
                                    handle.complete(result);
                                } catch (Throwable error) {
                                    local.get().remove(request.sessionId, pending);
                                    handle.fail(error);
                                }
                            }));
        } catch (RejectedExecutionException error) {
            local.get().remove(request.sessionId, pending);
            throw new ProtocolException(503, "UNAVAILABLE", "Agent server is shutting down");
        }
        return handle;
    }

    public ChatResponse state(String sessionId) {
        return state(sessionId, true);
    }

    /** The inspect tool only needs progress, even for sessions with a large transcript. */
    public ChatResponse status(String sessionId) {
        return state(sessionId, false);
    }

    private ChatResponse state(String sessionId, boolean includeResults) {
        sessions.requireSession(sessionId);
        LocalRun active = local.get().get(sessionId);
        if (active != null) return running(sessionId, active.handle.runId);
        String runner = runners == null ? null : runners.binding(sessionId);
        if (runner != null)
            for (RunnerJob job : runners.unfinished(runner))
                if (sessionId.equals(job.request.sessionId)) {
                    try {
                        ChatResponse state = conversations.state(sessionId, includeResults);
                        if (job.request.runId.equals(state.runId)) return state;
                    } catch (ProtocolException missing) {
                        if (missing.status != 404) throw missing;
                    }
                    return running(sessionId, job.request.runId);
                }
        return conversations.state(sessionId, includeResults);
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
        for (LocalRun active : local.get().values())
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
        for (java.util.Map<String, LocalRun> map : local.all())
            for (LocalRun active : map.values()) active.stop.stop();
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

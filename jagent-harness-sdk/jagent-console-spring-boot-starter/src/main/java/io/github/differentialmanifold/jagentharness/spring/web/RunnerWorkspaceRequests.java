package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.web.context.request.async.DeferredResult;

/** Short-lived, read-only UI lookups. All filesystem access happens on the runner. */
final class RunnerWorkspaceRequests {
    private final ConcurrentMap<String, Pending> requests = new ConcurrentHashMap<>();

    DeferredResult<WorkspaceListing> browse(String runner, String path) {
        if (path != null && path.length() > 2048)
            throw new ProtocolException(400, "INVALID_PATH", "Workspace path is too long");
        WorkspaceRequest request = new WorkspaceRequest();
        request.requestId = UUID.randomUUID().toString();
        request.path = path;
        DeferredResult<WorkspaceListing> result =
                new DeferredResult<>(
                        10000L,
                        new ProtocolException(
                                504,
                                "RUNNER_TIMEOUT",
                                "Runner did not respond to the directory lookup"));
        Pending pending = new Pending(request, result);
        if (requests.putIfAbsent(runner, pending) != null)
            throw new ProtocolException(
                    409, "LOOKUP_PENDING", "A directory lookup is already in progress");
        result.onCompletion(() -> requests.remove(runner, pending));
        result.onTimeout(() -> requests.remove(runner, pending));
        result.onError(error -> requests.remove(runner, pending));
        return result;
    }

    WorkspaceRequest poll(String runner) {
        Pending pending = requests.get(runner);
        if (pending == null) return null;
        synchronized (pending) {
            long now = System.currentTimeMillis();
            if (pending.result.isSetOrExpired() || now - pending.lastDelivery < 2000) return null;
            pending.lastDelivery = now;
            return pending.request;
        }
    }

    void complete(String runner, WorkspaceListing listing) {
        Pending pending = requests.get(runner);
        if (pending == null || !pending.request.requestId.equals(listing.requestId)) return;
        if (requests.remove(runner, pending)) {
            if (listing.error != null)
                pending.result.setErrorResult(
                        new ProtocolException(400, "WORKSPACE_ERROR", listing.error));
            else pending.result.setResult(listing);
        }
    }

    void close() {
        for (Pending pending : requests.values())
            pending.result.setErrorResult(
                    new ProtocolException(503, "SERVER_STOPPING", "Server is stopping"));
        requests.clear();
    }

    private static final class Pending {
        final WorkspaceRequest request;
        final DeferredResult<WorkspaceListing> result;
        long lastDelivery;

        Pending(WorkspaceRequest request, DeferredResult<WorkspaceListing> result) {
            this.request = request;
            this.result = result;
        }
    }
}

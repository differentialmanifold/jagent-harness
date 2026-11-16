package io.github.differentialmanifold.jagentharness.core.runner;

import io.github.differentialmanifold.jagentharness.core.execution.ProtocolException;
import io.github.differentialmanifold.jagentharness.core.identity.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Short-lived, read-only UI lookups. All filesystem access happens on the runner. */
final class RunnerWorkspaceRequests {
    private final UserState<ConcurrentMap<String, Pending>> requests =
            new UserState<>(() -> new ConcurrentHashMap<>());

    CompletableFuture<WorkspaceListing> browse(String runner, String path) {
        if (path != null && path.length() > 2048)
            throw new ProtocolException(400, "INVALID_PATH", "Workspace path is too long");
        WorkspaceRequest request = new WorkspaceRequest();
        request.requestId = UUID.randomUUID().toString();
        request.path = path;
        CompletableFuture<WorkspaceListing> result = new CompletableFuture<>();
        Pending pending = new Pending(request, result);
        if (requests.get().putIfAbsent(runner, pending) != null)
            throw new ProtocolException(
                    409, "LOOKUP_PENDING", "A directory lookup is already in progress");
        result.whenComplete(
                UserContext.callback((value, error) -> requests.get().remove(runner, pending)));
        return result;
    }

    WorkspaceRequest poll(String runner) {
        Pending pending = requests.get().get(runner);
        if (pending == null) return null;
        synchronized (pending) {
            long now = System.currentTimeMillis();
            if (pending.result.isDone() || now - pending.lastDelivery < 2000) return null;
            pending.lastDelivery = now;
            return pending.request;
        }
    }

    void complete(String runner, WorkspaceListing listing) {
        Pending pending = requests.get().get(runner);
        if (pending == null || !pending.request.requestId.equals(listing.requestId)) return;
        if (requests.get().remove(runner, pending)) {
            if (listing.error != null)
                pending.result.completeExceptionally(
                        new ProtocolException(400, "WORKSPACE_ERROR", listing.error));
            else pending.result.complete(listing);
        }
    }

    void expire() {
        long now = System.currentTimeMillis();
        for (Pending pending : requests.get().values())
            if (now - pending.createdAt >= 10000)
                pending.result.completeExceptionally(
                        new ProtocolException(
                                504,
                                "RUNNER_TIMEOUT",
                                "Runner did not respond to the directory lookup"));
    }

    void close() {
        for (java.util.Map<String, Pending> map : requests.all())
            for (Pending pending : map.values())
                pending.result.completeExceptionally(
                        new ProtocolException(503, "SERVER_STOPPING", "Server is stopping"));
        requests.get().clear();
    }

    private static final class Pending {
        final WorkspaceRequest request;
        final CompletableFuture<WorkspaceListing> result;
        long lastDelivery;
        final long createdAt = System.currentTimeMillis();

        Pending(WorkspaceRequest request, CompletableFuture<WorkspaceListing> result) {
            this.request = request;
            this.result = result;
        }
    }
}

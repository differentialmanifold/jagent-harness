package io.github.differentialmanifold.jagentharness.core.execution;

import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** A subscription to execution progress. Disconnecting an observer never cancels the execution. */
public final class RunHandle implements AutoCloseable {
    public final String sessionId;
    public final String runId;
    private final CompletableFuture<ChatResponse> result = new CompletableFuture<>();
    private Consumer<StreamEvent> listener;
    private Runnable cleanup;
    private boolean closed;
    private boolean started;

    public RunHandle(String sessionId, String runId, Consumer<StreamEvent> listener) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.listener = listener;
        result.whenComplete((value, error) -> close());
    }

    public CompletableFuture<ChatResponse> result() {
        return result;
    }

    public synchronized void onClose(Runnable cleanup) {
        if (closed) cleanup.run();
        else this.cleanup = cleanup;
    }

    public synchronized void emit(StreamEvent event) {
        if (closed || listener == null) return;
        if ("agent_start".equals(event.type)) {
            if (started) return;
            started = true;
        }
        try {
            listener.accept(event);
        } catch (RuntimeException disconnected) {
            close();
        }
    }

    public void complete(ChatResponse response) {
        result.complete(response);
    }

    public void fail(Throwable error) {
        result.completeExceptionally(error);
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        listener = null;
        if (cleanup != null) cleanup.run();
    }
}

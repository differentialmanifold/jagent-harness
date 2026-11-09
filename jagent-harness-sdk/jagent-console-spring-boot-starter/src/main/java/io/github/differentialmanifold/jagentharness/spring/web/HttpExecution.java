package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.agent.ActiveRunException;
import io.github.differentialmanifold.jagentharness.core.execution.*;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.core.session.SessionNotFoundException;
import io.github.differentialmanifold.jagentharness.protocol.*;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ErrorResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** HTTP observation only; task ownership and lifetime belong to the execution services. */
final class HttpExecution implements AutoCloseable {
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread thread = new Thread(r, "http-stream-heartbeat");
                        thread.setDaemon(true);
                        return thread;
                    });

    SseEmitter stream(Function<Consumer<StreamEvent>, RunHandle> submit) {
        SseEmitter emitter = new SseEmitter(0L);
        RunHandle handle = submit.apply(event -> send(emitter, event.type, event));
        ScheduledFuture<?> heartbeat =
                timer.scheduleAtFixedRate(
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().comment("keepalive"));
                            } catch (Exception disconnected) {
                                emitter.complete();
                                handle.close();
                            }
                        },
                        15,
                        15,
                        TimeUnit.SECONDS);
        Runnable cleanup =
                () -> {
                    heartbeat.cancel(false);
                    handle.close();
                };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        handle.result()
                .whenComplete(
                        (response, error) -> {
                            heartbeat.cancel(false);
                            try {
                                send(
                                        emitter,
                                        error == null ? "response" : "error",
                                        error == null ? response : errorBody(error));
                                emitter.complete();
                            } catch (RuntimeException disconnected) {
                                emitter.complete();
                            }
                        });
        return emitter;
    }

    static <T> DeferredResult<T> result(CompletableFuture<T> future) {
        DeferredResult<T> result = new DeferredResult<>(0L);
        future.whenComplete(
                (value, error) -> {
                    if (error == null) result.setResult(value);
                    else result.setErrorResult(unwrap(error));
                });
        return result;
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    static Map<String, Object> errorBody(Throwable failure) {
        Throwable error = unwrap(failure);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", error.getMessage() == null ? error.toString() : error.getMessage());
        int status = 500;
        if (error instanceof ProtocolException) status = ((ProtocolException) error).status;
        else if (error instanceof ModelProviderException) status = 502;
        else if (error instanceof SessionNotFoundException) status = 404;
        else if (error instanceof ActiveRunException) status = 409;
        else if (error instanceof IllegalArgumentException) status = 400;
        body.put("status", status);
        body.put(
                "code",
                error instanceof ProtocolException
                        ? ((ProtocolException) error).code
                        : ErrorResponse.codeFor(status));
        return body;
    }

    private static void send(SseEmitter emitter, String type, Object body) {
        try {
            emitter.send(SseEmitter.event().name(type).data(body));
        } catch (Exception error) {
            throw new IllegalStateException("Observer disconnected", error);
        }
    }

    public void close() {
        timer.shutdownNow();
    }
}

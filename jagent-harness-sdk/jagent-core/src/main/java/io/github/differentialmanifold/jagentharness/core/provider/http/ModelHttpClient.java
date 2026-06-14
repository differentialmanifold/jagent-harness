package io.github.differentialmanifold.jagentharness.core.provider.http;

import java.io.IOException;

import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;

public interface ModelHttpClient {

    ModelHttpResponse postJson(ModelHttpRequest request) throws IOException;

    <T> T postStream(ModelHttpRequest request, ModelHttpStreamHandler<T> handler) throws IOException;

    default ModelHttpResponse postJson(ModelHttpRequest request, StopSignal stopSignal) throws IOException {
        StopSignal effectiveSignal = stopSignal == null ? StopSignal.none() : stopSignal;
        effectiveSignal.throwIfAborted();
        ModelHttpResponse response = postJson(request);
        effectiveSignal.throwIfAborted();
        return response;
    }

    default <T> T postStream(ModelHttpRequest request,
                             ModelHttpStreamHandler<T> handler,
                             StopSignal stopSignal) throws IOException {
        StopSignal effectiveSignal = stopSignal == null ? StopSignal.none() : stopSignal;
        effectiveSignal.throwIfAborted();
        T result = postStream(request, handler);
        effectiveSignal.throwIfAborted();
        return result;
    }
}

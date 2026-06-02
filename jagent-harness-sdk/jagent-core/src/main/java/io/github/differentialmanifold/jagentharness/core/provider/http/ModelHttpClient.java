package io.github.differentialmanifold.jagentharness.core.provider.http;

import java.io.IOException;

public interface ModelHttpClient {

    ModelHttpResponse postJson(ModelHttpRequest request) throws IOException;

    <T> T postStream(ModelHttpRequest request, ModelHttpStreamHandler<T> handler) throws IOException;
}

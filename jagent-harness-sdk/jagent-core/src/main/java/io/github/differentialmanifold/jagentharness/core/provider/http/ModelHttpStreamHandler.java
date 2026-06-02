package io.github.differentialmanifold.jagentharness.core.provider.http;

import java.io.IOException;
import java.io.InputStream;

public interface ModelHttpStreamHandler<T> {

    T handle(InputStream inputStream) throws IOException;
}

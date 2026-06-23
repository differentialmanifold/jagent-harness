package io.github.differentialmanifold.jagentharness.core.provider.http;

import java.io.IOException;

public class ModelHttpException extends IOException {

    private final int statusCode;
    private final String body;

    public ModelHttpException(int statusCode, String body) {
        super("Model provider returned HTTP " + statusCode + ": " + (body == null ? "" : body));
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getBody() {
        return body;
    }
}

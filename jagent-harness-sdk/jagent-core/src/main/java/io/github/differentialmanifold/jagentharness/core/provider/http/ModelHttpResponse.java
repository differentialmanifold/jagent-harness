package io.github.differentialmanifold.jagentharness.core.provider.http;

public class ModelHttpResponse {

    private final int statusCode;
    private final String body;

    public ModelHttpResponse(int statusCode, String body) {
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

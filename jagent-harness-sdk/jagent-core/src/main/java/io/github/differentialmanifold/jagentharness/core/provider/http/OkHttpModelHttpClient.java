package io.github.differentialmanifold.jagentharness.core.provider.http;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class OkHttpModelHttpClient implements ModelHttpClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;

    public OkHttpModelHttpClient(int timeoutSeconds) {
        int timeout = Math.max(1, timeoutSeconds);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .build();
    }

    public OkHttpModelHttpClient(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public ModelHttpResponse postJson(ModelHttpRequest request) throws IOException {
        try (Response response = client.newCall(buildRequest(request)).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody == null ? "" : responseBody.string();
            if (!response.isSuccessful()) {
                throw new IOException("Model provider returned HTTP " + response.code() + ": " + body);
            }
            return new ModelHttpResponse(response.code(), body);
        }
    }

    @Override
    public <T> T postStream(ModelHttpRequest request, ModelHttpStreamHandler<T> handler) throws IOException {
        try (Response response = client.newCall(buildRequest(request)).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String body = responseBody == null ? "" : responseBody.string();
                throw new IOException("Model provider returned HTTP " + response.code() + ": " + body);
            }
            if (responseBody == null) {
                throw new IOException("Model provider returned empty response body");
            }
            return handler.handle(responseBody.byteStream());
        }
    }

    private Request buildRequest(ModelHttpRequest request) {
        RequestBody body = RequestBody.create(request.getBody(), JSON);
        Request.Builder builder = new Request.Builder()
                .url(request.getUrl())
                .post(body);
        for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }
}

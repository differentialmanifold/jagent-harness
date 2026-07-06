package io.github.differentialmanifold.jagentharness.core.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.differentialmanifold.jagentharness.core.agent.MutableStopSignal;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelDeltaConsumer;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpClient;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpRequest;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpResponse;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpStreamHandler;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderTest {

    @Test
    void existingConstructorUsesConfiguredApiKey() {
        OpenAiCompatibleProviderConfig config = config(false);
        config.setApiKey("configured-token");
        RecordingHttpClient httpClient = new RecordingHttpClient();
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(config, new ObjectMapper(), httpClient);

        provider.chat(request());

        assertEquals(Collections.singletonList("Bearer configured-token"), httpClient.authorizationHeaders);
    }

    @Test
    void resolvesAccessTokenForEveryRequestAndOmitsBlankToken() {
        AtomicReference<String> token = new AtomicReference<String>("first-token");
        RecordingHttpClient httpClient = new RecordingHttpClient();
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                config(false),
                new ObjectMapper(),
                httpClient,
                token::get);

        provider.chat(request());
        token.set("second-token");
        provider.chat(request());
        token.set(" ");
        provider.chat(request());

        assertEquals(3, httpClient.authorizationHeaders.size());
        assertEquals("Bearer first-token", httpClient.authorizationHeaders.get(0));
        assertEquals("Bearer second-token", httpClient.authorizationHeaders.get(1));
        assertEquals(null, httpClient.authorizationHeaders.get(2));
    }

    @Test
    void streamDisabledUsesNonStreamingRequestAndEmitsSingleDelta() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
            byte[] response = ("{\"choices\":[{\"message\":{\"content\":\"hello\",\"tool_calls\":[]}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();
        try {
            OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig();
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            config.setStreamEnabled(false);

            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(config, new ObjectMapper());
            ModelRequest request = new ModelRequest();
            request.setModel("test-model");
            request.setMessages(Collections.emptyList());

            List<String> deltas = new ArrayList<String>();
            ModelResponse response = provider.chat(request, deltas::add);

            assertEquals("hello", response.getContent());
            assertEquals(Collections.singletonList("hello"), deltas);
            assertTrue(requestBody.get().contains("\"stream\":false"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamingResponseCapturesReasoningContentAndEmitsReasoningDeltas() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            readAll(exchange.getRequestBody());
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think \"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n"
                    + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();
        try {
            OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig();
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            config.setStreamEnabled(true);

            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(config, new ObjectMapper());
            ModelRequest request = new ModelRequest();
            request.setModel("test-model");
            request.setMessages(Collections.emptyList());
            List<String> reasoningDeltas = new ArrayList<String>();
            List<String> contentDeltas = new ArrayList<String>();

            ModelResponse response = provider.chat(request, new ModelDeltaConsumer() {
                @Override
                public void onContentDelta(String delta) {
                    contentDeltas.add(delta);
                }

                @Override
                public void onReasoningDelta(String delta) {
                    reasoningDeltas.add(delta);
                }
            });

            assertEquals("think ", response.getReasoningContent());
            assertEquals("answer", response.getContent());
            assertEquals(Collections.singletonList("think "), reasoningDeltas);
            assertEquals(Collections.singletonList("answer"), contentDeltas);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancelsStreamingHttpCallWhenStopRequested() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            readAll(exchange.getRequestBody());
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                for (int index = 0; index < 100; index++) {
                    String event = "data: {\"choices\":[{\"delta\":{\"content\":\"chunk\"}}]}\n\n";
                    outputStream.write(event.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                    Thread.sleep(100);
                }
            } catch (IOException ignored) {
                // The client closes the response body when Call.cancel() is invoked.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setStreamEnabled(true);
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(config, new ObjectMapper());
        ModelRequest request = new ModelRequest();
        request.setModel("test-model");
        request.setMessages(Collections.emptyList());
        MutableStopSignal control = new MutableStopSignal();
        CountDownLatch firstDelta = new CountDownLatch(1);
        List<String> deltas = Collections.synchronizedList(new ArrayList<String>());
        Future<ModelResponse> responseFuture = executor.submit(() -> provider.chat(
                request,
                delta -> {
                    deltas.add(delta);
                    firstDelta.countDown();
                },
                control));

        try {
            assertTrue(firstDelta.await(3, TimeUnit.SECONDS));
            assertTrue(control.requestStop());

            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> responseFuture.get(3, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof StopRequestedException);
            assertFalse(deltas.isEmpty());
        } finally {
            responseFuture.cancel(true);
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void marksRetryableHttpFailures() throws Exception {
        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> callServerReturningStatus(503));

        assertTrue(exception.isRetryable());
    }

    @Test
    void marksClientHttpFailuresAsNonRetryable() throws Exception {
        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> callServerReturningStatus(400));

        assertFalse(exception.isRetryable());
    }

    private void callServerReturningStatus(int statusCode) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            readAll(exchange.getRequestBody());
            byte[] response = ("{\"error\":\"status " + statusCode + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();
        try {
            OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig();
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            config.setStreamEnabled(false);

            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(config, new ObjectMapper());
            ModelRequest request = new ModelRequest();
            request.setModel("test-model");
            request.setMessages(Collections.emptyList());

            provider.chat(request);
        } finally {
            server.stop(0);
        }
    }

    private OpenAiCompatibleProviderConfig config(boolean streamEnabled) {
        OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig();
        config.setBaseUrl("http://model.example/v1");
        config.setStreamEnabled(streamEnabled);
        return config;
    }

    private ModelRequest request() {
        ModelRequest request = new ModelRequest();
        request.setModel("test-model");
        request.setMessages(Collections.emptyList());
        return request;
    }

    private static byte[] readAll(java.io.InputStream inputStream) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private static class RecordingHttpClient implements ModelHttpClient {

        private final List<String> authorizationHeaders = new ArrayList<String>();

        @Override
        public ModelHttpResponse postJson(ModelHttpRequest request) {
            authorizationHeaders.add(request.getHeaders().get("Authorization"));
            return new ModelHttpResponse(
                    200,
                    "{\"choices\":[{\"message\":{\"content\":\"ok\",\"tool_calls\":[]}}]}");
        }

        @Override
        public <T> T postStream(ModelHttpRequest request, ModelHttpStreamHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
    }

}

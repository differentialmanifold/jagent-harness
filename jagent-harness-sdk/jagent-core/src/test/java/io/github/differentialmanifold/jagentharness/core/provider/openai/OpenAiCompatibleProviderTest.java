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
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderTest {

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

    private static byte[] readAll(java.io.InputStream inputStream) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

}

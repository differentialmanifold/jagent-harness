package io.github.differentialmanifold.jagentharness.core.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.differentialmanifold.jagentharness.core.agent.RunControl;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpClient;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpRequest;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpResponse;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpStreamHandler;
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
    void checksStopSignalBeforeEachSseChunk() {
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"second\"}}]}\n\n"
                + "data: [DONE]\n\n";
        ModelHttpClient httpClient = new StaticStreamHttpClient(stream);
        OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig();
        config.setBaseUrl("http://model.example/v1");
        config.setStreamEnabled(true);
        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider(config, new ObjectMapper(), httpClient);
        ModelRequest request = new ModelRequest();
        request.setModel("test-model");
        request.setMessages(Collections.emptyList());
        RunControl control = new RunControl();
        List<String> deltas = new ArrayList<String>();

        assertThrows(
                StopRequestedException.class,
                () -> provider.chat(request, delta -> {
                    deltas.add(delta);
                    control.requestStop();
                }, control));

        assertEquals(Collections.singletonList("first"), deltas);
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

    private static class StaticStreamHttpClient implements ModelHttpClient {
        private final byte[] content;

        private StaticStreamHttpClient(String content) {
            this.content = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public ModelHttpResponse postJson(ModelHttpRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T postStream(ModelHttpRequest request,
                                ModelHttpStreamHandler<T> handler) throws IOException {
            return handler.handle(new ByteArrayInputStream(content));
        }
    }
}

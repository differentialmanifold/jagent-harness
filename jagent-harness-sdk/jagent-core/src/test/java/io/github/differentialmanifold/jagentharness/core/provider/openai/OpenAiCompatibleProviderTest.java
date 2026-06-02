package io.github.differentialmanifold.jagentharness.core.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

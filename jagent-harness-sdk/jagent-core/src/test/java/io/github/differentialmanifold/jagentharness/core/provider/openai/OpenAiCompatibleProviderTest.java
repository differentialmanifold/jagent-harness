package io.github.differentialmanifold.jagentharness.core.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.differentialmanifold.jagentharness.core.agent.MutableStopSignal;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelDeltaConsumer;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpClient;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpRequest;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpResponse;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpStreamHandler;
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
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderTest {

    private ModelResponse stream(String data) {
        ModelHttpClient client =
                new ModelHttpClient() {
                    public ModelHttpResponse postJson(ModelHttpRequest request) {
                        throw new UnsupportedOperationException();
                    }

                    public <T> T postStream(
                            ModelHttpRequest request, ModelHttpStreamHandler<T> handler)
                            throws IOException {
                        return handler.handle(
                                new java.io.ByteArrayInputStream(
                                        data.getBytes(StandardCharsets.UTF_8)));
                    }
                };
        return new OpenAiCompatibleProvider(config(true), new ObjectMapper(), client)
                .chat(request(), ModelDeltaConsumer.contentOnly(value -> {}));
    }

    @Test
    void rejectsCleanEofWithoutACompletionMarker() {
        String partial = "data: {\"choices\":[{\"delta\":{\"content\":\"partial answer\"}}]}\n\n";
        ModelProviderException error =
                assertThrows(ModelProviderException.class, () -> stream(partial));
        assertTrue(error.isRetryable());
        assertEquals("partial answer", stream(partial + "data: [DONE]\n\n").getContent());
        assertEquals(
                "partial answer",
                stream(
                                partial
                                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                        .getContent());
    }

    @Test
    void separatesUnindexedCallsByIdAndAppendsTheirArguments() {
        String first =
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"read\",\"arguments\":\"{\"}},{\"id\":\"b\",\"function\":{\"name\":\"write\",\"arguments\":\"{}\"}}]}}]}\n\n";
        String second =
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"a\",\"function\":{\"arguments\":\"}\"}}]}}]}\n\n";
        ModelResponse response = stream(first + second + "data: [DONE]\n\n");
        assertEquals(2, response.getToolCalls().size());
        assertEquals("read", response.getToolCalls().get(0).getName());
        assertEquals("{}", response.getToolCalls().get(0).getArgumentsJson());
        assertEquals("b", response.getToolCalls().get(1).getToolCallId());
        assertEquals("write", response.getToolCalls().get(1).getName());
        assertThrows(
                ModelProviderException.class,
                () ->
                        stream(
                                first
                                        + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"}\"}}]}}]}\n\n"));
    }

    @Test
    void keepsIndexedOrderAndResolvesIdOnlyContinuation() {
        ModelResponse response =
                stream(
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"write\",\"arguments\":\"{\"}},{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}}]}\n\n"
                                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"b\",\"function\":{\"arguments\":\"}\"}}]}}]}\n\ndata: [DONE]\n\n");
        assertEquals(2, response.getToolCalls().size());
        assertEquals("a", response.getToolCalls().get(0).getToolCallId());
        assertEquals("{}", response.getToolCalls().get(1).getArgumentsJson());
    }

    @Test
    void neverTreatsProviderErrorEventsAsSuccessfulAnswers() {
        for (String data :
                java.util.Arrays.asList(
                        "event: error\ndata: upstream failed\n\n",
                        "event: error\n\n",
                        "data: {\"error\":{\"message\":\"invalid key\",\"code\":401}}\n\n")) {
            ModelProviderException error =
                    assertThrows(ModelProviderException.class, () -> stream(data));
            assertFalse(error.isRetryable());
        }
        ModelProviderException temporary =
                assertThrows(
                        ModelProviderException.class,
                        () ->
                                stream(
                                        "event: error\ndata: {\"error\":{\"message\":\"busy\",\"code\":503}}\n\n"));
        assertTrue(temporary.isRetryable());
        assertTrue(temporary.getMessage().contains("busy"));
        ModelProviderException json =
                assertThrows(
                        ModelProviderException.class,
                        () ->
                                new OpenAiCompatibleProvider(
                                                config(false),
                                                new ObjectMapper(),
                                                new UsageHttpClient(
                                                        "{\"error\":{\"message\":\"invalid key\",\"code\":401}}"))
                                        .chat(request()));
        assertFalse(json.isRetryable());
    }

    @Test
    void serializesTextMessagesAsStringsAndImageMessagesAsOpenAiContentParts() throws Exception {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider(config(false), objectMapper, httpClient);

        ModelRequest textRequest = request();
        textRequest.setMessages(Collections.singletonList(AgentMessage.user("s1", "plain text")));
        provider.chat(textRequest);

        AgentMessage multimodal = AgentMessage.user("s1", "describe this");
        multimodal.setImages(
                Collections.singletonList(
                        new MessageImage(
                                "screen.png",
                                "image/png",
                                "data:image/png;base64,aGVsbG8=",
                                "high")));
        ModelRequest imageRequest = request();
        imageRequest.setMessages(Collections.singletonList(multimodal));
        provider.chat(imageRequest);

        JsonNode plainContent =
                objectMapper
                        .readTree(httpClient.requestBodies.get(0))
                        .path("messages")
                        .path(0)
                        .path("content");
        assertTrue(plainContent.isTextual());
        assertEquals("plain text", plainContent.asText());

        JsonNode parts =
                objectMapper
                        .readTree(httpClient.requestBodies.get(1))
                        .path("messages")
                        .path(0)
                        .path("content");
        assertTrue(parts.isArray());
        assertEquals(2, parts.size());
        assertEquals("text", parts.path(0).path("type").asText());
        assertEquals("describe this", parts.path(0).path("text").asText());
        assertEquals("image_url", parts.path(1).path("type").asText());
        assertEquals(
                "data:image/png;base64,aGVsbG8=",
                parts.path(1).path("image_url").path("url").asText());
        assertEquals("high", parts.path(1).path("image_url").path("detail").asText());
    }

    @Test
    void placesToolImagesAfterAllToolResults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RecordingHttpClient client = new RecordingHttpClient();
        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider(config(false), mapper, client);
        AgentMessage assistant = AgentMessage.assistant("s", "", Collections.emptyList());
        assistant.setToolCalls(
                java.util.Arrays.asList(
                        new io.github.differentialmanifold.jagentharness.core.tool.ToolCall(
                                "a", "screen", "{}"),
                        new io.github.differentialmanifold.jagentharness.core.tool.ToolCall(
                                "b", "status", "{}")));
        AgentMessage a = AgentMessage.tool("s", "a", "screen", "Captured"),
                b = AgentMessage.tool("s", "b", "status", "Ready");
        a.setImages(
                Collections.singletonList(
                        new MessageImage(
                                "screen.png", "image/png", "https://example.com/screen.png")));
        ModelRequest request = request();
        request.setMessages(java.util.Arrays.asList(assistant, a, b));
        provider.chat(request);
        JsonNode messages = mapper.readTree(client.requestBodies.get(0)).path("messages");
        assertEquals("assistant", messages.get(0).path("role").asText());
        assertEquals("tool", messages.get(1).path("role").asText());
        assertEquals("tool", messages.get(2).path("role").asText());
        assertEquals("user", messages.get(3).path("role").asText());
        assertEquals(
                "https://example.com/screen.png",
                messages.get(3).path("content").get(1).path("image_url").path("url").asText());
    }

    @Test
    void supportsImageOnlyUserMessages() throws Exception {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider(config(false), objectMapper, httpClient);
        AgentMessage message = AgentMessage.user("s1", "");
        message.setImages(
                Collections.singletonList(
                        new MessageImage(
                                "photo.jpg", "image/jpeg", "data:image/jpeg;base64,aGVsbG8=")));
        ModelRequest request = request();
        request.setMessages(Collections.singletonList(message));

        provider.chat(request);

        JsonNode parts =
                objectMapper
                        .readTree(httpClient.requestBodies.get(0))
                        .path("messages")
                        .path(0)
                        .path("content");
        assertEquals(1, parts.size());
        assertEquals("image_url", parts.path(0).path("type").asText());
    }

    @Test
    void existingConstructorUsesConfiguredApiKey() {
        OpenAiCompatibleProviderConfig config = config(false);
        config.setApiKey("configured-token");
        RecordingHttpClient httpClient = new RecordingHttpClient();
        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider(config, new ObjectMapper(), httpClient);

        provider.chat(request());

        assertEquals(
                Collections.singletonList("Bearer configured-token"),
                httpClient.authorizationHeaders);
    }

    @Test
    void resolvesAccessTokenForEveryRequestAndOmitsBlankToken() {
        AtomicReference<String> token = new AtomicReference<String>("first-token");
        RecordingHttpClient httpClient = new RecordingHttpClient();
        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider(
                        config(false), new ObjectMapper(), httpClient, token::get);

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
    void appendsChatCompletionsBeforeBaseUrlQueryAndPreservesFullEndpointQuery() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        OpenAiCompatibleProviderConfig baseConfig = config(false);
        baseConfig.setBaseUrl("http://model.example/v1/?api-version=2026-08-01");
        OpenAiCompatibleProvider baseProvider =
                new OpenAiCompatibleProvider(baseConfig, new ObjectMapper(), httpClient);
        OpenAiCompatibleProviderConfig endpointConfig = config(false);
        endpointConfig.setBaseUrl(
                "http://model.example/v1/chat/completions?api-version=2026-08-01");
        OpenAiCompatibleProvider endpointProvider =
                new OpenAiCompatibleProvider(endpointConfig, new ObjectMapper(), httpClient);

        baseProvider.chat(request());
        endpointProvider.chat(request());

        assertEquals(
                "http://model.example/v1/chat/completions?api-version=2026-08-01",
                httpClient.requestUrls.get(0));
        assertEquals(
                "http://model.example/v1/chat/completions?api-version=2026-08-01",
                httpClient.requestUrls.get(1));
    }

    @Test
    void streamDisabledUsesNonStreamingRequestAndEmitsSingleDelta() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    requestBody.set(
                            new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
                    byte[] response =
                            ("{\"choices\":[{\"message\":{\"content\":\"hello\",\"tool_calls\":[]}}]}")
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

            OpenAiCompatibleProvider provider =
                    new OpenAiCompatibleProvider(config, new ObjectMapper());
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
    void parsesNestedUsageFromNonStreamingResponse() throws Exception {
        UsageHttpClient httpClient =
                new UsageHttpClient(
                        "{\"choices\":[{\"message\":{\"content\":\"hello\",\"tool_calls\":[]}}],"
                                + "\"usage\":{\"prompt_tokens\":24,\"completion_tokens\":251,"
                                + "\"completion_tokens_details\":{\"reasoning_tokens\":239},"
                                + "\"prompt_tokens_details\":{\"cached_tokens\":3},\"total_tokens\":275}}");
        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider(config(false), new ObjectMapper(), httpClient);

        ModelResponse response = provider.chat(request());

        assertEquals(Integer.valueOf(24), response.getUsage().getPromptTokens());
        assertEquals(Integer.valueOf(251), response.getUsage().getCompletionTokens());
        assertEquals(Integer.valueOf(239), response.getUsage().getReasoningTokens());
        assertEquals(Integer.valueOf(3), response.getUsage().getCachedTokens());
        assertEquals(Integer.valueOf(275), response.getUsage().getTotalTokens());
        assertEquals(Integer.valueOf(36), response.getUsage().getActualContextTokens());
    }

    @Test
    void parsesFlatUsageFromNonStreamingResponse() throws Exception {
        UsageHttpClient httpClient =
                new UsageHttpClient(
                        "{\"choices\":[{\"message\":{\"content\":\"hello\",\"tool_calls\":[]}}],"
                                + "\"usage\":{\"prompt_tokens\":24,\"completion_tokens\":251,"
                                + "\"reasoning_tokens\":239,\"cached_tokens\":3,\"total_tokens\":275}}");
        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider(config(false), new ObjectMapper(), httpClient);

        ModelResponse response = provider.chat(request());

        assertEquals(Integer.valueOf(239), response.getUsage().getReasoningTokens());
        assertEquals(Integer.valueOf(3), response.getUsage().getCachedTokens());
        assertEquals(Integer.valueOf(36), response.getUsage().getActualContextTokens());
    }

    @Test
    void streamingResponseCapturesReasoningContentAndEmitsReasoningDeltas() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    requestBody.set(
                            new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
                    byte[] response =
                            ("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think \"}}]}\n\n"
                                            + "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n"
                                            + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":24,\"completion_tokens\":251,"
                                            + "\"reasoning_tokens\":239,\"cached_tokens\":0,\"total_tokens\":275}}\n\n"
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

            OpenAiCompatibleProvider provider =
                    new OpenAiCompatibleProvider(config, new ObjectMapper());
            ModelRequest request = new ModelRequest();
            request.setModel("test-model");
            request.setMessages(Collections.emptyList());
            List<String> reasoningDeltas = new ArrayList<String>();
            List<String> contentDeltas = new ArrayList<String>();

            ModelResponse response =
                    provider.chat(
                            request,
                            new ModelDeltaConsumer() {
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
            assertEquals(Integer.valueOf(36), response.getUsage().getActualContextTokens());
            assertTrue(requestBody.get().contains("\"stream_options\""));
            assertTrue(requestBody.get().contains("\"include_usage\":true"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancelsStreamingHttpCallWhenStopRequested() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    readAll(exchange.getRequestBody());
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream outputStream = exchange.getResponseBody()) {
                        for (int index = 0; index < 100; index++) {
                            String event =
                                    "data: {\"choices\":[{\"delta\":{\"content\":\"chunk\"}}]}\n\n";
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
        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider(config, new ObjectMapper());
        ModelRequest request = new ModelRequest();
        request.setModel("test-model");
        request.setMessages(Collections.emptyList());
        MutableStopSignal control = new MutableStopSignal();
        CountDownLatch firstDelta = new CountDownLatch(1);
        List<String> deltas = Collections.synchronizedList(new ArrayList<String>());
        Future<ModelResponse> responseFuture =
                executor.submit(
                        () ->
                                provider.chat(
                                        request,
                                        delta -> {
                                            deltas.add(delta);
                                            firstDelta.countDown();
                                        },
                                        control));

        try {
            assertTrue(firstDelta.await(3, TimeUnit.SECONDS));
            assertTrue(control.requestStop());

            ExecutionException exception =
                    assertThrows(
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
        ModelProviderException exception =
                assertThrows(ModelProviderException.class, () -> callServerReturningStatus(503));

        assertTrue(exception.isRetryable());
    }

    @Test
    void marksClientHttpFailuresAsNonRetryable() throws Exception {
        ModelProviderException exception =
                assertThrows(ModelProviderException.class, () -> callServerReturningStatus(400));

        assertFalse(exception.isRetryable());
    }

    private void callServerReturningStatus(int statusCode) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    readAll(exchange.getRequestBody());
                    byte[] response =
                            ("{\"error\":\"status " + statusCode + "\"}")
                                    .getBytes(StandardCharsets.UTF_8);
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

            OpenAiCompatibleProvider provider =
                    new OpenAiCompatibleProvider(config, new ObjectMapper());
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
        private final List<String> requestBodies = new ArrayList<String>();
        private final List<String> requestUrls = new ArrayList<String>();

        @Override
        public ModelHttpResponse postJson(ModelHttpRequest request) {
            authorizationHeaders.add(request.getHeaders().get("Authorization"));
            requestBodies.add(request.getBody());
            requestUrls.add(request.getUrl());
            return new ModelHttpResponse(
                    200, "{\"choices\":[{\"message\":{\"content\":\"ok\",\"tool_calls\":[]}}]}");
        }

        @Override
        public <T> T postStream(ModelHttpRequest request, ModelHttpStreamHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
    }

    private static class UsageHttpClient implements ModelHttpClient {

        private final String body;

        private UsageHttpClient(String body) {
            this.body = body;
        }

        @Override
        public ModelHttpResponse postJson(ModelHttpRequest request) {
            return new ModelHttpResponse(200, body);
        }

        @Override
        public <T> T postStream(ModelHttpRequest request, ModelHttpStreamHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
    }
}

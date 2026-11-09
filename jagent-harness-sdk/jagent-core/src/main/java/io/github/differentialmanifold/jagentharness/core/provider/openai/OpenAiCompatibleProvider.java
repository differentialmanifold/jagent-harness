package io.github.differentialmanifold.jagentharness.core.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.FailedTurn;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelDeltaConsumer;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.provider.ModelUsage;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpClient;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpException;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpRequest;
import io.github.differentialmanifold.jagentharness.core.provider.http.OkHttpModelHttpClient;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class OpenAiCompatibleProvider implements ModelProvider {

    private final OpenAiCompatibleProviderConfig config;
    private final ObjectMapper objectMapper;
    private final ModelHttpClient httpClient;
    private final Supplier<String> accessTokenSupplier;

    public OpenAiCompatibleProvider(
            OpenAiCompatibleProviderConfig config, ObjectMapper objectMapper) {
        this(
                config,
                objectMapper,
                new OkHttpModelHttpClient(config.getTimeoutSeconds()),
                config::getApiKey);
    }

    public OpenAiCompatibleProvider(
            OpenAiCompatibleProviderConfig config,
            ObjectMapper objectMapper,
            ModelHttpClient httpClient) {
        this(config, objectMapper, httpClient, config::getApiKey);
    }

    public OpenAiCompatibleProvider(
            OpenAiCompatibleProviderConfig config,
            ObjectMapper objectMapper,
            Supplier<String> accessTokenSupplier) {
        this(
                config,
                objectMapper,
                new OkHttpModelHttpClient(config.getTimeoutSeconds()),
                accessTokenSupplier);
    }

    public OpenAiCompatibleProvider(
            OpenAiCompatibleProviderConfig config,
            ObjectMapper objectMapper,
            ModelHttpClient httpClient,
            Supplier<String> accessTokenSupplier) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.accessTokenSupplier =
                accessTokenSupplier == null ? config::getApiKey : accessTokenSupplier;
    }

    @Override
    public String getName() {
        return "openai-compatible";
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        return chatNonStreaming(request, StopSignal.none());
    }

    private ModelResponse chatNonStreaming(ModelRequest request, StopSignal stopSignal) {
        ObjectNode payload = buildPayload(request, false);
        try {
            String body = postJson(objectMapper.writeValueAsString(payload), stopSignal);
            return parseResponse(body);
        } catch (IOException e) {
            if (stopSignal.isAborted()) {
                throw new StopRequestedException(e);
            }
            throw new ModelProviderException(
                    "Model provider request failed: " + e.getMessage(), e, isRetryable(e));
        }
    }

    @Override
    public ModelResponse chat(ModelRequest request, Consumer<String> contentDeltaConsumer) {
        return chat(
                request, ModelDeltaConsumer.contentOnly(contentDeltaConsumer), StopSignal.none());
    }

    @Override
    public ModelResponse chat(
            ModelRequest request, Consumer<String> contentDeltaConsumer, StopSignal stopSignal) {
        return chat(request, ModelDeltaConsumer.contentOnly(contentDeltaConsumer), stopSignal);
    }

    @Override
    public ModelResponse chat(ModelRequest request, ModelDeltaConsumer deltaConsumer) {
        return chat(request, deltaConsumer, StopSignal.none());
    }

    @Override
    public ModelResponse chat(
            ModelRequest request, ModelDeltaConsumer deltaConsumer, StopSignal stopSignal) {
        StopSignal effectiveSignal = stopSignal == null ? StopSignal.none() : stopSignal;
        effectiveSignal.throwIfAborted();
        if (!config.isStreamEnabled()) {
            ModelResponse response = chatNonStreaming(request, effectiveSignal);
            if (deltaConsumer != null) {
                if (response.getReasoningContent() != null
                        && !response.getReasoningContent().isEmpty()) {
                    deltaConsumer.onReasoningDelta(response.getReasoningContent());
                }
                if (response.getContent() != null && !response.getContent().isEmpty()) {
                    deltaConsumer.onContentDelta(response.getContent());
                }
            }
            effectiveSignal.throwIfAborted();
            return response;
        }
        ObjectNode payload = buildPayload(request, true);
        try {
            return postStream(
                    objectMapper.writeValueAsString(payload), deltaConsumer, effectiveSignal);
        } catch (IOException e) {
            if (effectiveSignal.isAborted()) {
                throw new StopRequestedException(e);
            }
            throw new ModelProviderException(
                    "Model provider stream request failed: " + e.getMessage(), e, isRetryable(e));
        }
    }

    private String postJson(String body, StopSignal stopSignal) throws IOException {
        return httpClient
                .postJson(
                        new ModelHttpRequest(resolveChatCompletionsUrl(), headers(), body),
                        stopSignal)
                .getBody();
    }

    private ModelResponse postStream(
            String body, ModelDeltaConsumer deltaConsumer, StopSignal stopSignal)
            throws IOException {
        return httpClient.postStream(
                new ModelHttpRequest(resolveChatCompletionsUrl(), headers(), body),
                inputStream -> parseStreamResponse(inputStream, deltaConsumer),
                stopSignal);
    }

    private ObjectNode buildPayload(ModelRequest request, boolean stream) {
        String model = trimToEmpty(request.getModel());
        if (model.isEmpty()) {
            throw new ModelProviderException(
                    "Model name is required. Configure harness.model.model.");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("stream", stream);
        if (stream && config.isIncludeUsage()) {
            ObjectNode streamOptions = objectMapper.createObjectNode();
            streamOptions.put("include_usage", true);
            payload.set("stream_options", streamOptions);
        }
        if (request.getTemperature() != null) {
            payload.put("temperature", request.getTemperature());
        }
        payload.set("messages", buildMessages(request));
        ArrayNode tools = buildTools(request);
        if (tools.size() > 0) {
            payload.set("tools", tools);
            payload.put("tool_choice", "auto");
        }
        return payload;
    }

    private ArrayNode buildMessages(ModelRequest request) {
        ArrayNode messages = objectMapper.createArrayNode();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().trim().isEmpty()) {
            ObjectNode system = objectMapper.createObjectNode();
            system.put("role", AgentMessage.ROLE_SYSTEM);
            system.put("content", request.getSystemPrompt());
            messages.add(system);
        }

        if (request.getMessages() == null) {
            return messages;
        }
        for (AgentMessage message : FailedTurn.context(request.getMessages())) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("role", message.getRole());
            if (AgentMessage.ROLE_TOOL.equals(message.getRole())) {
                node.put("tool_call_id", message.getToolCallId());
                if (message.getToolName() != null) {
                    node.put("name", message.getToolName());
                }
                node.put("content", valueOrEmpty(message.getContent()));
            } else if (AgentMessage.ROLE_ASSISTANT.equals(message.getRole())
                    && message.getToolCalls() != null
                    && !message.getToolCalls().isEmpty()) {
                if (message.getContent() == null || message.getContent().isEmpty()) {
                    node.putNull("content");
                } else {
                    node.put("content", message.getContent());
                }
                ArrayNode calls = objectMapper.createArrayNode();
                for (ToolCall call : message.getToolCalls()) {
                    ObjectNode callNode = objectMapper.createObjectNode();
                    callNode.put("id", call.getToolCallId());
                    callNode.put("type", "function");
                    ObjectNode function = objectMapper.createObjectNode();
                    function.put("name", call.getName());
                    function.put("arguments", valueOrEmpty(call.getArgumentsJson()));
                    callNode.set("function", function);
                    calls.add(callNode);
                }
                node.set("tool_calls", calls);
            } else if (AgentMessage.ROLE_USER.equals(message.getRole())
                    && message.getImages() != null
                    && !message.getImages().isEmpty()) {
                node.set("content", buildMultimodalContent(message));
            } else {
                node.put("content", valueOrEmpty(message.getContent()));
            }
            messages.add(node);
        }
        return messages;
    }

    private ArrayNode buildMultimodalContent(AgentMessage message) {
        ArrayNode content = objectMapper.createArrayNode();
        if (message.getContent() != null && !message.getContent().trim().isEmpty()) {
            ObjectNode text = objectMapper.createObjectNode();
            text.put("type", "text");
            text.put("text", message.getContent());
            content.add(text);
        }
        for (MessageImage image : message.getImages()) {
            if (image == null || trimToEmpty(image.getUrl()).isEmpty()) {
                throw new ModelProviderException("Every message image must have a URL");
            }
            ObjectNode imagePart = objectMapper.createObjectNode();
            imagePart.put("type", "image_url");
            ObjectNode imageUrl = objectMapper.createObjectNode();
            imageUrl.put("url", image.getUrl());
            String detail = trimToEmpty(image.getDetail());
            if (!detail.isEmpty()) {
                imageUrl.put("detail", detail);
            }
            imagePart.set("image_url", imageUrl);
            content.add(imagePart);
        }
        return content;
    }

    private ArrayNode buildTools(ModelRequest request) {
        ArrayNode tools = objectMapper.createArrayNode();
        if (request.getTools() == null) {
            return tools;
        }
        for (ToolDefinition tool : request.getTools()) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("type", "function");
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.set("parameters", tool.getParametersSchema());
            wrapper.set("function", function);
            tools.add(wrapper);
        }
        return tools;
    }

    private ModelResponse parseResponse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        checkProviderError(root);
        JsonNode message = root.path("choices").path(0).path("message");
        ModelResponse response = new ModelResponse();
        JsonNode contentNode = message.path("content");
        if (!contentNode.isMissingNode() && !contentNode.isNull()) {
            response.setContent(contentNode.asText());
        }
        JsonNode reasoningNode = message.path("reasoning_content");
        if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
            response.setReasoningContent(reasoningNode.asText());
        }
        List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isArray()) {
            for (JsonNode node : toolCallsNode) {
                String id = node.path("id").asText();
                String name = node.path("function").path("name").asText();
                JsonNode arguments = node.path("function").path("arguments");
                String argsJson = arguments.isTextual() ? arguments.asText() : arguments.toString();
                toolCalls.add(new ToolCall(id, name, argsJson));
            }
        }
        response.setToolCalls(toolCalls);
        response.setUsage(parseUsage(root.path("usage")));
        response.setRawJson(body);
        return response;
    }

    private ModelResponse parseStreamResponse(
            InputStream inputStream, ModelDeltaConsumer deltaConsumer) throws IOException {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StreamAccumulator accumulator = new StreamAccumulator();
        StringBuilder data = new StringBuilder();
        String event = "";
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                consumeSseData(event, data, accumulator, deltaConsumer);
                data.setLength(0);
                event = "";
            } else if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
            }
        }
        consumeSseData(event, data, accumulator, deltaConsumer);
        return accumulator.toResponse(objectMapper);
    }

    private void consumeSseData(
            String event,
            StringBuilder data,
            StreamAccumulator accumulator,
            ModelDeltaConsumer deltaConsumer)
            throws IOException {
        if ("error".equals(event)) {
            JsonNode error = null;
            try {
                error = objectMapper.readTree(data.toString());
            } catch (IOException ignored) {
                // Some gateways send plain text in error events.
            }
            checkProviderError(error);
            throw providerError(error, data.toString());
        }
        if (data.length() == 0) {
            return;
        }
        String value = data.toString();
        if ("[DONE]".equals(value)) {
            return;
        }
        accumulator.rawChunks.add(value);
        JsonNode root = objectMapper.readTree(value);
        checkProviderError(root);
        ModelUsage usage = parseUsage(root.path("usage"));
        if (usage != null) {
            accumulator.usage = usage;
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode delta = choice.path("delta");
        JsonNode reasoningNode = delta.path("reasoning_content");
        if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
            String reasoningDelta = reasoningNode.asText();
            accumulator.reasoningContent.append(reasoningDelta);
            if (deltaConsumer != null && !reasoningDelta.isEmpty()) {
                deltaConsumer.onReasoningDelta(reasoningDelta);
            }
        }
        JsonNode contentNode = delta.path("content");
        if (!contentNode.isMissingNode() && !contentNode.isNull()) {
            String contentDelta = contentNode.asText();
            accumulator.content.append(contentDelta);
            if (deltaConsumer != null && !contentDelta.isEmpty()) {
                deltaConsumer.onContentDelta(contentDelta);
            }
        }

        JsonNode toolCallsNode = delta.path("tool_calls");
        if (toolCallsNode.isArray()) {
            for (JsonNode node : toolCallsNode) {
                ToolCallAccumulator toolCall = accumulator.toolCall(node);
                JsonNode function = node.path("function");
                if (function.hasNonNull("name")) {
                    toolCall.name.append(function.path("name").asText());
                }
                if (function.hasNonNull("arguments")) {
                    toolCall.arguments.append(function.path("arguments").asText());
                }
            }
        }
    }

    private void checkProviderError(JsonNode root) {
        if (root != null && root.hasNonNull("error")) {
            throw providerError(root.get("error"), root.get("error").toString());
        }
    }

    private ModelProviderException providerError(JsonNode error, String fallback) {
        String message = error == null ? fallback : error.path("message").asText(fallback);
        int status = error == null ? 0 : error.path("status").asInt(error.path("code").asInt(0));
        String code = error == null ? "" : error.path("code").asText("");
        boolean retryable =
                status == 429
                        || status == 500
                        || status == 502
                        || status == 503
                        || status == 504
                        || "rate_limit_exceeded".equals(code)
                        || "server_error".equals(code);
        return new ModelProviderException(
                "Model provider error: " + (message.isEmpty() ? "unspecified error" : message),
                null,
                retryable);
    }

    private ModelUsage parseUsage(JsonNode usageNode) {
        if (usageNode == null
                || usageNode.isMissingNode()
                || usageNode.isNull()
                || !usageNode.isObject()) {
            return null;
        }
        ModelUsage usage = new ModelUsage();
        usage.setPromptTokens(integerValue(usageNode.path("prompt_tokens")));
        usage.setCompletionTokens(integerValue(usageNode.path("completion_tokens")));
        usage.setTotalTokens(integerValue(usageNode.path("total_tokens")));

        Integer reasoningTokens =
                integerValue(usageNode.path("completion_tokens_details").path("reasoning_tokens"));
        if (reasoningTokens == null) {
            reasoningTokens = integerValue(usageNode.path("reasoning_tokens"));
        }
        usage.setReasoningTokens(reasoningTokens);

        Integer cachedTokens =
                integerValue(usageNode.path("prompt_tokens_details").path("cached_tokens"));
        if (cachedTokens == null) {
            cachedTokens = integerValue(usageNode.path("cached_tokens"));
        }
        usage.setCachedTokens(cachedTokens);

        return usage.hasTokenCounts() ? usage : null;
    }

    private Integer integerValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            try {
                return Integer.valueOf(value.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String resolveChatCompletionsUrl() {
        String base = trimToEmpty(config.getBaseUrl());
        if (base.isEmpty()) {
            throw new ModelProviderException(
                    "Model provider base URL is required. Configure harness.model.base-url.");
        }

        int queryIndex = base.indexOf('?');
        int fragmentIndex = base.indexOf('#');
        int suffixIndex = base.length();
        if (queryIndex >= 0) {
            suffixIndex = queryIndex;
        }
        if (fragmentIndex >= 0 && fragmentIndex < suffixIndex) {
            suffixIndex = fragmentIndex;
        }

        String endpoint = base.substring(0, suffixIndex);
        String suffix = base.substring(suffixIndex);
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (!endpoint.endsWith("/chat/completions")) {
            endpoint += "/chat/completions";
        }
        return endpoint + suffix;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        String accessToken = accessToken();
        if (!accessToken.isEmpty()) {
            headers.put("Authorization", "Bearer " + accessToken);
        }
        return headers;
    }

    private String accessToken() {
        try {
            return trimToEmpty(accessTokenSupplier.get());
        } catch (RuntimeException e) {
            throw new ModelProviderException("Failed to obtain model access token", e);
        }
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isRetryable(IOException exception) {
        if (exception instanceof ModelHttpException) {
            int statusCode = ((ModelHttpException) exception).getStatusCode();
            return statusCode == 429
                    || statusCode == 500
                    || statusCode == 502
                    || statusCode == 503
                    || statusCode == 504;
        }
        return true;
    }

    private static class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoningContent = new StringBuilder();
        private final Map<Integer, ToolCallAccumulator> toolCalls =
                new TreeMap<Integer, ToolCallAccumulator>();
        private final Map<String, ToolCallAccumulator> callsById = new LinkedHashMap<>();
        private final List<ToolCallAccumulator> unindexedCalls = new ArrayList<>();
        private final List<String> rawChunks = new ArrayList<String>();
        private ModelUsage usage;

        private ToolCallAccumulator toolCall(JsonNode node) {
            Integer index = node.hasNonNull("index") ? node.path("index").asInt() : null;
            String id = node.hasNonNull("id") ? node.path("id").asText() : null;
            if (id != null && id.isEmpty()) id = null;
            ToolCallAccumulator call = index == null ? null : toolCalls.get(index);
            ToolCallAccumulator identified = id == null ? null : callsById.get(id);
            if (call != null && identified != null && call != identified
                    || call != null && id != null && call.id != null && !id.equals(call.id)) {
                throw new ModelProviderException("Conflicting streamed tool call identity");
            }
            if (call == null) call = identified;
            if (call == null && index == null && id == null) {
                int count = toolCalls.size() + unindexedCalls.size();
                if (count > 1)
                    throw new ModelProviderException(
                            "Ambiguous streamed tool call without index or id");
                if (count == 1)
                    call =
                            toolCalls.isEmpty()
                                    ? unindexedCalls.get(0)
                                    : toolCalls.values().iterator().next();
            }
            if (call == null) {
                call = new ToolCallAccumulator();
                if (index == null) unindexedCalls.add(call);
            }
            if (index != null) {
                if (call.index != null && !index.equals(call.index))
                    throw new ModelProviderException("Conflicting streamed tool call index");
                call.index = index;
                toolCalls.put(index, call);
                unindexedCalls.remove(call);
            }
            if (id != null) {
                call.id = id;
                callsById.put(id, call);
            }
            return call;
        }

        private ModelResponse toResponse(ObjectMapper objectMapper) {
            ModelResponse response = new ModelResponse();
            response.setContent(content.toString());
            response.setReasoningContent(reasoningContent.toString());
            List<ToolCall> calls = new ArrayList<ToolCall>();
            List<ToolCallAccumulator> ordered = new ArrayList<>(toolCalls.values());
            ordered.addAll(unindexedCalls);
            for (ToolCallAccumulator toolCall : ordered) {
                String id =
                        toolCall.id == null || toolCall.id.isEmpty()
                                ? "call_stream_"
                                        + (toolCall.index == null ? calls.size() : toolCall.index)
                                : toolCall.id;
                calls.add(
                        new ToolCall(id, toolCall.name.toString(), toolCall.arguments.toString()));
            }
            response.setToolCalls(calls);
            response.setUsage(usage);

            ObjectNode raw = objectMapper.createObjectNode();
            raw.put("stream", true);
            ArrayNode chunks = objectMapper.createArrayNode();
            for (String chunk : rawChunks) {
                chunks.add(chunk);
            }
            raw.set("chunks", chunks);
            response.setRawJson(raw.toString());
            return response;
        }
    }

    private static class ToolCallAccumulator {
        private Integer index;
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}

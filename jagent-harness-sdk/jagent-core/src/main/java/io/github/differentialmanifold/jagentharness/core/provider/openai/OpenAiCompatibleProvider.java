package io.github.differentialmanifold.jagentharness.core.provider.openai;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelDeltaConsumer;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpClient;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpRequest;
import io.github.differentialmanifold.jagentharness.core.provider.http.OkHttpModelHttpClient;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;

public class OpenAiCompatibleProvider implements ModelProvider {

    private final OpenAiCompatibleProviderConfig config;
    private final ObjectMapper objectMapper;
    private final ModelHttpClient httpClient;

    public OpenAiCompatibleProvider(OpenAiCompatibleProviderConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, new OkHttpModelHttpClient(config.getTimeoutSeconds()));
    }

    public OpenAiCompatibleProvider(OpenAiCompatibleProviderConfig config,
                                    ObjectMapper objectMapper,
                                    ModelHttpClient httpClient) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
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
            throw new ModelProviderException("Model provider request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ModelResponse chat(ModelRequest request, Consumer<String> contentDeltaConsumer) {
        return chat(request, ModelDeltaConsumer.contentOnly(contentDeltaConsumer), StopSignal.none());
    }

    @Override
    public ModelResponse chat(ModelRequest request,
                              Consumer<String> contentDeltaConsumer,
                              StopSignal stopSignal) {
        return chat(request, ModelDeltaConsumer.contentOnly(contentDeltaConsumer), stopSignal);
    }

    @Override
    public ModelResponse chat(ModelRequest request, ModelDeltaConsumer deltaConsumer) {
        return chat(request, deltaConsumer, StopSignal.none());
    }

    @Override
    public ModelResponse chat(ModelRequest request,
                              ModelDeltaConsumer deltaConsumer,
                              StopSignal stopSignal) {
        StopSignal effectiveSignal = stopSignal == null ? StopSignal.none() : stopSignal;
        effectiveSignal.throwIfAborted();
        if (!config.isStreamEnabled()) {
            ModelResponse response = chatNonStreaming(request, effectiveSignal);
            if (deltaConsumer != null) {
                if (response.getReasoningContent() != null && !response.getReasoningContent().isEmpty()) {
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
                    objectMapper.writeValueAsString(payload),
                    deltaConsumer,
                    effectiveSignal);
        } catch (IOException e) {
            if (effectiveSignal.isAborted()) {
                throw new StopRequestedException(e);
            }
            throw new ModelProviderException("Model provider stream request failed: " + e.getMessage(), e);
        }
    }

    private String postJson(String body, StopSignal stopSignal) throws IOException {
        return httpClient.postJson(
                new ModelHttpRequest(resolveChatCompletionsUrl(), headers(), body),
                stopSignal).getBody();
    }

    private ModelResponse postStream(String body,
                                     ModelDeltaConsumer deltaConsumer,
                                     StopSignal stopSignal) throws IOException {
        return httpClient.postStream(
                new ModelHttpRequest(resolveChatCompletionsUrl(), headers(), body),
                inputStream -> parseStreamResponse(inputStream, deltaConsumer),
                stopSignal);
    }

    private ObjectNode buildPayload(ModelRequest request, boolean stream) {
        String model = trimToEmpty(request.getModel());
        if (model.isEmpty()) {
            throw new ModelProviderException("Model name is required. Configure harness.model.model.");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("stream", stream);
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
        for (AgentMessage message : request.getMessages()) {
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
            } else {
                node.put("content", valueOrEmpty(message.getContent()));
            }
            messages.add(node);
        }
        return messages;
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
        response.setRawJson(body);
        return response;
    }

    private ModelResponse parseStreamResponse(InputStream inputStream,
                                              ModelDeltaConsumer deltaConsumer) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StreamAccumulator accumulator = new StreamAccumulator();
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                consumeSseData(data, accumulator, deltaConsumer);
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
            }
        }
        consumeSseData(data, accumulator, deltaConsumer);
        return accumulator.toResponse(objectMapper);
    }

    private void consumeSseData(StringBuilder data,
                                StreamAccumulator accumulator,
                                ModelDeltaConsumer deltaConsumer) throws IOException {
        if (data.length() == 0) {
            return;
        }
        String value = data.toString();
        if ("[DONE]".equals(value)) {
            return;
        }
        accumulator.rawChunks.add(value);
        JsonNode root = objectMapper.readTree(value);
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
                int index = node.path("index").asInt(0);
                ToolCallAccumulator toolCall = accumulator.toolCalls.get(index);
                if (toolCall == null) {
                    toolCall = new ToolCallAccumulator();
                    accumulator.toolCalls.put(index, toolCall);
                }
                if (node.hasNonNull("id")) {
                    toolCall.id = node.path("id").asText();
                }
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

    private String resolveChatCompletionsUrl() {
        String base = trimToEmpty(config.getBaseUrl());
        if (base.isEmpty()) {
            throw new ModelProviderException("Model provider base URL is required. Configure harness.model.base-url.");
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (base.endsWith("/")) {
            return base + "chat/completions";
        }
        return base + "/chat/completions";
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        String apiKey = trimToEmpty(config.getApiKey());
        if (!apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return headers;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoningContent = new StringBuilder();
        private final Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<Integer, ToolCallAccumulator>();
        private final List<String> rawChunks = new ArrayList<String>();

        private ModelResponse toResponse(ObjectMapper objectMapper) {
            ModelResponse response = new ModelResponse();
            response.setContent(content.toString());
            response.setReasoningContent(reasoningContent.toString());
            List<ToolCall> calls = new ArrayList<ToolCall>();
            for (Map.Entry<Integer, ToolCallAccumulator> entry : toolCalls.entrySet()) {
                ToolCallAccumulator toolCall = entry.getValue();
                String id = toolCall.id == null || toolCall.id.isEmpty()
                        ? "call_stream_" + entry.getKey()
                        : toolCall.id;
                calls.add(new ToolCall(id, toolCall.name.toString(), toolCall.arguments.toString()));
            }
            response.setToolCalls(calls);

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
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}

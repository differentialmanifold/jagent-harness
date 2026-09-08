package io.github.differentialmanifold.jagentharness.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Drives ordinary conversation turns. Requests and local effects are never automatically replayed.
 */
public final class AgentClient {
    private final AgentTransport transport;
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    public AgentClient(AgentTransport transport, Collection<? extends ToolDefinition> tools) {
        this.transport = Objects.requireNonNull(transport);
        for (ToolDefinition tool : tools)
            if (this.tools.put(tool.getName(), tool) != null)
                throw new IllegalArgumentException("Duplicate tool: " + tool.getName());
    }

    public ChatResponse run(ChatRequest request, ClientRunOptions options) throws IOException {
        Objects.requireNonNull(options);
        if (request.messages == null || request.messages.isEmpty())
            throw new IllegalArgumentException("Messages are required");
        if (options.stop.isAborted() && !"tool".equals(request.messages.get(0).role))
            throw new java.util.concurrent.CancellationException("Operation stopped");
        if (request.clientTools.isEmpty())
            for (ToolDefinition tool : tools.values()) request.clientTools.add(tool.descriptor());
        Set<String> advertised = new HashSet<>();
        for (ToolDescriptor descriptor : request.clientTools) advertised.add(descriptor.name);
        final boolean[] started = {false};
        Consumer<StreamEvent> events =
                event -> {
                    // UI continuity is a client concern; each server request is an ordinary run
                    // call.
                    if ("agent_start".equals(event.type)) {
                        if (started[0]) return;
                        started[0] = true;
                    }
                    if (options.events != null) options.events.accept(event);
                };
        ChatRequest current = request;
        for (int exchanges = 0; exchanges < 128; exchanges++) {
            // In particular, do not retry an IOException: the server may have accepted the input.
            ChatResponse response = transport.chat(current, events);
            if (!Objects.equals(request.sessionId, response.sessionId))
                throw new IOException("Response belongs to another session");
            if (current.runId != null && !Objects.equals(current.runId, response.runId))
                throw new IOException("Response belongs to another operation");
            if (!Arrays.asList("REQUIRES_ACTION", "COMPLETED", "CANCELLED", "INTERRUPTED")
                    .contains(response.status))
                throw new IOException("Unsupported conversation status: " + response.status);
            if (!"REQUIRES_ACTION".equals(response.status)) return response;
            if (response.toolCalls == null || response.toolCalls.isEmpty())
                throw new IOException("Missing tool calls");
            ChatRequest next = new ChatRequest();
            next.sessionId = request.sessionId;
            next.runId = response.runId;
            next.stream = request.stream;
            next.clientTools = request.clientTools;
            next.clientInstructions = request.clientInstructions;
            Set<String> received = new HashSet<>();
            // Validate before executing any local effects; this is not replay/idempotency handling.
            for (ToolInvocation call : response.toolCalls)
                if (call == null
                        || call.toolCallId == null
                        || !received.add(call.toolCallId)
                        || call.arguments == null
                        || !advertised.contains(call.name))
                    throw new IOException("Invalid or unadvertised tool invocation");
            for (ToolInvocation call : response.toolCalls) {
                ToolExecutionResult value;
                emit(events, response, call, "tool_execution_start", null);
                if (options.stop.isAborted())
                    value = ToolExecutionResult.failure("Cancelled before execution");
                else {
                    ToolContext context =
                            new ToolContext(
                                            response.sessionId,
                                            response.runId,
                                            call.turnId,
                                            null,
                                            null,
                                            null,
                                            Collections.emptyMap(),
                                            options.stop)
                                    .withLocalContext(options.toolContext)
                                    .forToolCall(call.toolCallId, call.name);
                    try {
                        value =
                                tools.get(call.name)
                                        .execute(context, json.valueToTree(call.arguments));
                        if (value == null || value.content == null)
                            value = ToolExecutionResult.unknown("Tool returned no result");
                    } catch (Exception error) {
                        value = ToolExecutionResult.unknown(error.toString());
                    }
                }
                next.messages.add(ChatMessage.tool(call.toolCallId, value.status, value.content));
                emit(events, response, call, "tool_execution_end", value.content);
            }
            current = next;
        }
        throw new IOException(
                "Maximum conversation exchanges exceeded; inspect session history before continuing");
    }

    private void emit(
            Consumer<StreamEvent> sink,
            ChatResponse response,
            ToolInvocation call,
            String type,
            Object result) {
        StreamEvent event = new StreamEvent();
        event.eventId = "client_" + call.toolCallId + "_" + type;
        event.sessionId = response.sessionId;
        event.runId = response.runId;
        event.turnId = call.turnId;
        event.type = type;
        event.createdAt = java.time.Instant.now().toString();
        event.payload.put("toolCallId", call.toolCallId);
        event.payload.put("toolName", call.name);
        if (result != null) event.payload.put("result", result);
        sink.accept(event);
    }
}

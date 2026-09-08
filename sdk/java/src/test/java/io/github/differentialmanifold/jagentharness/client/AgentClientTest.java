package io.github.differentialmanifold.jagentharness.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentClientTest {
    final ObjectMapper json = new ObjectMapper();

    ToolDefinition tool(AtomicInteger calls) {
        return new ToolDefinition() {
            public String getName() {
                return "echo";
            }

            public String getDescription() {
                return "Echo text";
            }

            public JsonNode getParametersSchema() {
                return json.createObjectNode().put("type", "object");
            }

            public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
                calls.incrementAndGet();
                assertEquals("session", context.getSessionId());
                assertEquals("call", context.getCurrentToolCallId());
                return ToolExecutionResult.success(arguments.get("text").asText());
            }
        };
    }

    ChatRequest request() {
        ChatRequest request = new ChatRequest();
        request.sessionId = "session";
        request.messages.add(ChatMessage.user("hello"));
        request.clientInstructions = "Business instructions";
        return request;
    }

    ChatResponse response(boolean pending) {
        ChatResponse response = new ChatResponse();
        response.sessionId = "session";
        response.runId = "operation";
        response.status = pending ? "REQUIRES_ACTION" : "COMPLETED";
        if (pending) {
            ToolInvocation call = new ToolInvocation();
            call.toolCallId = "call";
            call.name = "echo";
            call.turnId = "turn";
            call.arguments.put("text", "value");
            response.toolCalls.add(call);
        }
        return response;
    }

    @Test
    void toolResultsUseOrdinaryChatAndSameDefinitionMetadata() throws Exception {
        AtomicInteger exchanges = new AtomicInteger(), effects = new AtomicInteger();
        AgentClient client =
                new AgentClient(
                        (request, events) -> {
                            assertEquals("echo", request.clientTools.get(0).name);
                            assertEquals("Business instructions", request.clientInstructions);
                            if (exchanges.getAndIncrement() == 0) return response(true);
                            assertEquals("tool", request.messages.get(0).role);
                            assertEquals("value", request.messages.get(0).content);
                            assertEquals("call", request.messages.get(0).toolCallId);
                            return response(false);
                        },
                        Collections.singletonList(tool(effects)));
        assertEquals("COMPLETED", client.run(request(), new ClientRunOptions()).status);
        assertEquals(2, exchanges.get());
        assertEquals(1, effects.get());
    }

    @Test
    void uncertainUserRequestIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        AgentClient client =
                new AgentClient(
                        (request, events) -> {
                            calls.incrementAndGet();
                            throw new IOException("lost response");
                        },
                        Collections.emptyList());
        assertThrows(IOException.class, () -> client.run(request(), new ClientRunOptions()));
        assertEquals(1, calls.get());
    }

    @Test
    void lostToolResultResponseDoesNotReplayRequestOrLocalEffect() {
        AtomicInteger calls = new AtomicInteger(), effects = new AtomicInteger();
        AgentClient client =
                new AgentClient(
                        (request, events) -> {
                            if (calls.getAndIncrement() == 0) return response(true);
                            throw new IOException("result accepted, response lost");
                        },
                        Collections.singletonList(tool(effects)));
        assertThrows(IOException.class, () -> client.run(request(), new ClientRunOptions()));
        assertEquals(2, calls.get());
        assertEquals(1, effects.get());
    }

    @Test
    void duplicateInvocationsFailBeforeAnyLocalEffect() {
        AtomicInteger effects = new AtomicInteger();
        ChatResponse pending = response(true);
        pending.toolCalls.add(pending.toolCalls.get(0));
        AgentClient client =
                new AgentClient(
                        (request, events) -> pending, Collections.singletonList(tool(effects)));
        assertThrows(IOException.class, () -> client.run(request(), new ClientRunOptions()));
        assertEquals(0, effects.get());
    }

    @Test
    void unadvertisedToolNeverExecutes() {
        AtomicInteger effects = new AtomicInteger();
        ChatResponse pending = response(true);
        pending.toolCalls.get(0).name = "hidden";
        AgentClient client =
                new AgentClient(
                        (request, events) -> pending, Collections.singletonList(tool(effects)));
        assertThrows(IOException.class, () -> client.run(request(), new ClientRunOptions()));
        assertEquals(0, effects.get());
    }

    @Test
    void repeatedServerStartsAppearAsOneUiOperation() throws Exception {
        AtomicInteger calls = new AtomicInteger(), starts = new AtomicInteger();
        AgentClient client =
                new AgentClient(
                        (request, events) -> {
                            StreamEvent event = new StreamEvent();
                            event.type = "agent_start";
                            events.accept(event);
                            return response(calls.getAndIncrement() == 0);
                        },
                        Collections.singletonList(tool(new AtomicInteger())));
        client.run(
                request(),
                new ClientRunOptions()
                        .onEvent(
                                event -> {
                                    if ("agent_start".equals(event.type)) starts.incrementAndGet();
                                }));
        assertEquals(1, starts.get());
    }
}

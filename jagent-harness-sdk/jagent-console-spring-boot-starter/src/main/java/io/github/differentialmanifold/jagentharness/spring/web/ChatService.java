package io.github.differentialmanifold.jagentharness.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.event.*;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRepository;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * HTTP application service over the original runner and session repositories. No continuation
 * store.
 */
public class ChatService {
    private final ObjectMapper json;
    private final AgentHarness agent;
    private final SessionStore sessions;
    private final SessionRepository records;
    private final TimelineEventRepository timeline;
    private final ToolRegistry tools;
    private final RunInputCoordinator inputs;
    private final AgentEventPublisher publisher;
    private final ConcurrentMap<String, Active> active = new ConcurrentHashMap<>();

    public ChatService(
            ObjectMapper json,
            AgentHarness agent,
            SessionStore sessions,
            SessionRepository records,
            TimelineEventRepository timeline,
            ToolRegistry tools,
            RunInputCoordinator inputs,
            AgentEventPublisher publisher) {
        this.json = json;
        this.agent = agent;
        this.sessions = sessions;
        this.records = records;
        this.timeline = timeline;
        this.tools = tools;
        this.inputs = inputs;
        this.publisher = publisher;
    }

    public ChatResponse chat(ChatRequest request, Consumer<StreamEvent> listener) {
        validate(request);
        sessions.requireSession(request.sessionId);
        Active running = new Active();
        if (active.putIfAbsent(request.sessionId, running) != null)
            throw new ProtocolException(
                    409, "SESSION_BUSY", "Another request is modifying this session");
        try {
            List<AgentMessage> history = sessions.findMessages(request.sessionId);
            List<AgentMessage> incoming = new ArrayList<>();
            boolean toolResults = "tool".equals(request.messages.get(0).role);
            Map<String, ToolCall> pending = ConversationHistory.pendingTools(history);
            for (ChatMessage message : request.messages) {
                if (!toolResults) {
                    AgentMessage input =
                            AgentMessage.user(request.sessionId, content(message.content));
                    List<MessageImage> images = new ArrayList<>();
                    if (message.images != null)
                        for (Map<String, Object> image : message.images)
                            images.add(json.convertValue(image, MessageImage.class));
                    input.setImages(images);
                    incoming.add(input);
                } else {
                    ToolCall call = pending.get(message.toolCallId);
                    if (call == null)
                        throw new ProtocolException(
                                400,
                                "INVALID_TOOL_RESULT",
                                "No unanswered call in this session: " + message.toolCallId);
                    AgentMessage calling =
                            ConversationHistory.callingMessage(history, message.toolCallId);
                    running.runId = calling.getRunId();
                    AgentMessage result =
                            AgentMessage.tool(
                                    request.sessionId,
                                    call.getToolCallId(),
                                    call.getName(),
                                    "SUCCEEDED".equals(message.status)
                                            ? content(message.content)
                                            : content(message));
                    result.setTurnId(calling.getTurnId());
                    incoming.add(result);
                }
            }
            try {
                ConversationHistory.validateInput(request.sessionId, history, incoming);
            } catch (IllegalArgumentException error) {
                throw new ProtocolException(400, "INVALID_HISTORY", error.getMessage());
            }
            if (running.runId == null)
                running.runId = request.runId == null ? Ids.newId("run") : request.runId;
            if (toolResults && request.runId != null && !request.runId.equals(running.runId))
                throw new ProtocolException(
                        400, "WRONG_RUN", "Tool results belong to another operation");
            // The catalog is supplied on each ordinary conversation request.
            ClientCapabilities capabilities =
                    new ClientCapabilities(request.clientTools, request.clientInstructions);
            ToolContext context = new ToolContext(request.sessionId, running.runId, "validate");
            context.setClientCapabilities(capabilities);
            try {
                tools.all(context);
            } catch (IllegalStateException error) {
                throw new ProtocolException(400, "INVALID_TOOLS", error.getMessage());
            }
            if (toolResults && "CANCELLED".equals(snapshot(request.sessionId).status))
                running.stop();
            inputs.activateRun(request.sessionId, running.runId);
            Consumer<AgentEvent> sink =
                    event -> {
                        if (listener != null) listener.accept(ProtocolMapper.event(event, json));
                    };
            try {
                AgentRunResult result =
                        agent.run(
                                request.sessionId,
                                incoming,
                                AgentRunOptions.builder()
                                        .runId(running.runId)
                                        .clientCapabilities(capabilities)
                                        .eventConsumer(sink)
                                        .stopSignal(running)
                                        .runInputSource(inputs)
                                        .build());
                ChatResponse response = response(result);
                if (!"REQUIRES_ACTION".equals(response.status))
                    inputs.closeRun(request.sessionId, running.runId);
                return response;
            } catch (StopRequestedException stopped) {
                inputs.closeRun(request.sessionId, running.runId);
                ChatResponse response = new ChatResponse();
                response.sessionId = request.sessionId;
                response.runId = running.runId;
                response.status = "CANCELLED";
                return response;
            } catch (RuntimeException error) {
                inputs.closeRun(request.sessionId, running.runId);
                throw error;
            }
        } finally {
            active.remove(request.sessionId, running);
        }
    }

    public ChatResponse state(String sessionId) {
        sessions.requireSession(sessionId);
        ChatResponse response = snapshot(sessionId);
        Active running = active.get(sessionId);
        if (running != null) {
            response.runId = running.runId;
            response.status = "RUNNING";
            response.toolCalls.clear();
        }
        return response;
    }

    /** Inspection derives from the ordinary event/message history; it cannot authorize replay. */
    private ChatResponse snapshot(String sessionId) {
        List<AgentEvent> events = timeline.findBySessionId(sessionId);
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent event = events.get(i);
            if ("agent_requires_action".equals(event.getType())) {
                try {
                    return response(json.readValue(event.getPayloadJson(), AgentRunResult.class));
                } catch (Exception error) {
                    throw new IllegalStateException("Invalid conversation event", error);
                }
            }
            if (AgentEvent.AGENT_END.equals(event.getType())) {
                // Original timeline boundaries omit payloads; the answer lives in message history.
                ChatResponse response = new ChatResponse();
                response.sessionId = sessionId;
                response.runId = event.getRunId();
                response.status = "COMPLETED";
                List<AgentMessage> history = sessions.findMessages(sessionId);
                for (int j = history.size() - 1; j >= 0; j--) {
                    AgentMessage message = history.get(j);
                    if (Objects.equals(event.getRunId(), message.getRunId())
                            && AgentMessage.ROLE_ASSISTANT.equals(message.getRole())) {
                        response.answer = message.getContent();
                        break;
                    }
                }
                return response;
            }
            if (Arrays.asList(
                            AgentEvent.AGENT_START,
                            AgentEvent.AGENT_ERROR,
                            AgentEvent.AGENT_STOPPED)
                    .contains(event.getType())) {
                ChatResponse response = new ChatResponse();
                response.sessionId = sessionId;
                response.runId = event.getRunId();
                response.status =
                        AgentEvent.AGENT_STOPPED.equals(event.getType())
                                ? "CANCELLED"
                                : "INTERRUPTED";
                return response;
            }
        }
        throw new ProtocolException(404, "NOT_FOUND", "No operation for this session");
    }

    private ChatResponse response(AgentRunResult result) {
        ChatResponse response = new ChatResponse();
        response.sessionId = result.getSessionId();
        response.runId = result.getRunId();
        response.status = result.getStatus();
        response.answer = result.getAnswer();
        List<AgentMessage> history = sessions.findMessages(response.sessionId);
        Map<String, ToolCall> pending = ConversationHistory.pendingTools(history);
        for (ToolCall call : result.getToolCalls()) {
            if (!pending.containsKey(call.getToolCallId())) continue;
            ToolInvocation wire = new ToolInvocation();
            wire.toolCallId = call.getToolCallId();
            wire.name = call.getName();
            wire.turnId = ConversationHistory.callingMessage(history, wire.toolCallId).getTurnId();
            try {
                wire.arguments = json.readValue(call.getArgumentsJson(), Map.class);
            } catch (Exception error) {
                throw new IllegalArgumentException("Invalid tool arguments", error);
            }
            response.toolCalls.add(wire);
        }
        return response;
    }

    public void cancel(String runId) {
        for (Map.Entry<String, Active> entry : active.entrySet()) {
            if (runId.equals(entry.getValue().runId)) {
                entry.getValue().stop();
                return;
            }
        }
        for (SessionRecord session : records.findAll()) {
            ChatResponse current;
            try {
                current = state(session.getSessionId());
            } catch (ProtocolException missing) {
                continue;
            }
            if (!runId.equals(current.runId)) continue;
            if ("REQUIRES_ACTION".equals(current.status)) {
                publisher.publish(
                        session.getSessionId(),
                        runId,
                        null,
                        AgentEvent.AGENT_STOPPED,
                        Collections.singletonMap("status", "CANCELLED"));
                inputs.closeRun(session.getSessionId(), runId);
            }
            return;
        }
        throw new ProtocolException(404, "NOT_FOUND", "Operation not found");
    }

    private void validate(ChatRequest request) {
        if (request == null
                || request.sessionId == null
                || !request.sessionId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new ProtocolException(400, "INVALID_SESSION", "A valid sessionId is required");
        if (request.runId != null && !request.runId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new ProtocolException(400, "INVALID_RUN", "Invalid tracking ID");
        if (request.messages == null || request.messages.isEmpty() || request.messages.size() > 128)
            throw new ProtocolException(
                    400, "INVALID_MESSAGES", "Provide user input or tool results");
        String role = request.messages.get(0) == null ? null : request.messages.get(0).role;
        if (!Arrays.asList("user", "tool").contains(role))
            throw new ProtocolException(
                    400, "INVALID_ROLE", "Only user and tool messages are accepted");
        for (ChatMessage message : request.messages) {
            if (message == null || !role.equals(message.role) || message.content == null)
                throw new ProtocolException(
                        400, "INVALID_MESSAGES", "Invalid or mixed-role messages");
            if ("tool".equals(role)
                    && (message.toolCallId == null
                            || !Arrays.asList("SUCCEEDED", "FAILED", "UNKNOWN")
                                    .contains(message.status)))
                throw new ProtocolException(
                        400,
                        "INVALID_TOOL_RESULT",
                        "Tool results need a call ID, status and content");
            if ("user".equals(role) && message.images != null) {
                try {
                    List<
                                    io.github.differentialmanifold.jagentharness.spring.web.dto
                                            .ChatImageRequest>
                            images = new ArrayList<>();
                    for (Map<String, Object> image : message.images)
                        images.add(
                                json.convertValue(
                                        image,
                                        io.github.differentialmanifold.jagentharness.spring.web.dto
                                                .ChatImageRequest.class));
                    ImageInputValidator.normalize(images);
                } catch (IllegalArgumentException error) {
                    throw new ProtocolException(400, "INVALID_IMAGES", error.getMessage());
                }
            }
        }
        if (request.clientTools == null || request.clientTools.size() > 128)
            throw new ProtocolException(400, "INVALID_TOOLS", "Invalid tool catalog");
        Set<String> names = new HashSet<>();
        for (ToolDescriptor descriptor : request.clientTools)
            if (descriptor == null
                    || descriptor.name == null
                    || !descriptor.name.matches("[A-Za-z0-9_-]{1,64}")
                    || !names.add(descriptor.name)
                    || descriptor.parameters == null
                    || !"object".equals(descriptor.parameters.get("type")))
                throw new ProtocolException(
                        400,
                        "INVALID_TOOLS",
                        "Tool names and object schemas must be valid and unique");
    }

    private String content(Object value) {
        if (value instanceof String) return (String) value;
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid JSON", error);
        }
    }

    private static final class Active implements StopSignal {
        volatile String runId;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        public boolean isAborted() {
            return stopped.get();
        }

        public void throwIfAborted() {
            if (isAborted()) throw new StopRequestedException();
        }

        public StopRegistration onStop(Runnable action) {
            listeners.add(action);
            if (isAborted()) action.run();
            return () -> listeners.remove(action);
        }

        void stop() {
            if (stopped.compareAndSet(false, true)) for (Runnable action : listeners) action.run();
        }
    }
}

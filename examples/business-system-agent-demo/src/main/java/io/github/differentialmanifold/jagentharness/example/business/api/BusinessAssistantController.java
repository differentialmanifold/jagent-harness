package io.github.differentialmanifold.jagentharness.example.business.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentHarness;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/business-assistant")
public class BusinessAssistantController {

    private final AgentHarness agentHarness;
    private final SessionManager sessionManager;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;

    public BusinessAssistantController(AgentHarness agentHarness,
                                       SessionManager sessionManager,
                                       TaskExecutor taskExecutor,
                                       ObjectMapper objectMapper) {
        this.agentHarness = agentHarness;
        this.sessionManager = sessionManager;
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody BusinessChatRequest request) {
        BusinessChatRequest effectiveRequest = requireRequest(request);
        SessionRecord session = session(effectiveRequest.getSessionId());
        SseEmitter emitter = new SseEmitter(0L);
        taskExecutor.execute(() -> {
            try {
                agentHarness.run(
                        session.getSessionId(),
                        effectiveRequest.getMessage(),
                        AgentRunOptions.builder().eventConsumer(event -> sendEvent(emitter, event)).build());
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, session.getSessionId(), e);
                emitter.complete();
            }
        });
        return emitter;
    }

    private BusinessChatRequest requireRequest(BusinessChatRequest request) {
        if (request == null || isBlank(request.getMessage())) {
            throw new IllegalArgumentException("message is required");
        }
        request.setMessage(request.getMessage().trim());
        return request;
    }

    private SessionRecord session(String sessionId) {
        if (!isBlank(sessionId)) {
            return sessionManager.requireSession(sessionId.trim());
        }
        return sessionManager.createSession(
                "Business System Demo",
                null);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private JsonNode payload(String payloadJson) {
        if (isBlank(payloadJson)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode().put("raw", payloadJson);
        }
    }

    private void sendEvent(SseEmitter emitter, AgentEvent event) {
        if (!shouldStream(event)) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(event.getEventId())
                    .name(event.getType())
                    .data(eventResponse(event)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stream agent event", e);
        }
    }

    private void sendError(SseEmitter emitter, String sessionId, Exception exception) {
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("message", exception.getMessage());
            emitter.send(SseEmitter.event()
                    .name("agent_error")
                    .data(new BusinessAgentEventResponse(
                            null,
                            sessionId,
                            null,
                            "agent_error",
                            objectMapper.valueToTree(payload),
                            null)));
        } catch (Exception ignored) {
        }
    }

    private BusinessAgentEventResponse eventResponse(AgentEvent event) {
        return new BusinessAgentEventResponse(
                event.getEventId(),
                event.getSessionId(),
                event.getTurnId(),
                event.getType(),
                payload(event.getPayloadJson()),
                event.getCreatedAt() == null ? null : event.getCreatedAt().toString());
    }

    private boolean shouldStream(AgentEvent event) {
        if (event == null || isBlank(event.getSessionId()) || isBlank(event.getType())) {
            return false;
        }
        return !AgentEvent.AGENT_START.equals(event.getType())
                && !AgentEvent.AGENT_END.equals(event.getType())
                && !AgentEvent.MESSAGE_START.equals(event.getType())
                && !AgentEvent.MESSAGE_UPDATE.equals(event.getType())
                && !AgentEvent.MESSAGE_REASONING_UPDATE.equals(event.getType())
                && !AgentEvent.TOOL_EXECUTION_UPDATE.equals(event.getType());
    }
}

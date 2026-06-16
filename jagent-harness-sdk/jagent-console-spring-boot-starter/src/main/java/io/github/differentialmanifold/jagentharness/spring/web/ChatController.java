package io.github.differentialmanifold.jagentharness.spring.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.agent.AgentHarness;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopCoordinator;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopHandle;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestResult;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatRunRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatStopRequest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final SessionManager sessionManager;
    private final AgentHarness agentHarness;
    private final TaskExecutor agentTaskExecutor;
    private final RunStopCoordinator runStopCoordinator;

    public ChatController(SessionManager sessionManager,
                          AgentHarness agentHarness,
                          TaskExecutor agentTaskExecutor,
                          RunStopCoordinator runStopCoordinator) {
        this.sessionManager = sessionManager;
        this.agentHarness = agentHarness;
        this.agentTaskExecutor = agentTaskExecutor;
        this.runStopCoordinator = runStopCoordinator;
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestBody ChatRunRequest request) {
        ChatRunRequest effectiveRequest = requireRunRequest(request);
        String sessionId = effectiveRequest.getSessionId();
        String requestId = Ids.newId("req");
        RunStopHandle stopHandle = runStopCoordinator.register(requestId, sessionId);
        SseEmitter emitter = new SseEmitter(0L);
        try {
            agentTaskExecutor.execute(() -> {
                try {
                    AgentRunOptions options = optionsFrom(effectiveRequest)
                            .toBuilder()
                            .eventConsumer(event -> sendEvent(emitter, event))
                            .stopSignal(stopHandle)
                            .build();
                    agentHarness.run(
                            sessionId,
                            effectiveRequest.getContent(),
                            options);
                    emitter.complete();
                } catch (StopRequestedException e) {
                    emitter.complete();
                } catch (Exception e) {
                    sendAgentError(emitter, sessionId, e);
                    emitter.complete();
                } finally {
                    stopHandle.close();
                    Thread.interrupted();
                }
            });
        } catch (RuntimeException e) {
            stopHandle.close();
            throw e;
        }
        return ResponseEntity.ok()
                .header(REQUEST_ID_HEADER, requestId)
                .body(emitter);
    }

    @PostMapping("/requests/stop")
    public ResponseEntity<Void> stop(@RequestBody ChatStopRequest request) {
        String requestId = request == null ? null : request.getRequestId();
        StopRequestResult result = runStopCoordinator.requestStop(requireRequestId(requestId));
        if (result == StopRequestResult.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.accepted().build();
    }

    private ChatRunRequest requireRunRequest(ChatRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("sessionId and content are required");
        }
        request.setSessionId(requireSessionId(request.getSessionId()));
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("content is required");
        }
        sessionManager.requireSession(request.getSessionId());
        return request;
    }

    private String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        return sessionId.trim();
    }

    private String requireRequestId(String requestId) {
        String value = requestId == null ? "" : requestId.trim();
        if (value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException("requestId must contain 1-128 characters");
        }
        return value;
    }

    private void sendEvent(SseEmitter emitter, AgentEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.getEventId())
                    .name(event.getType())
                    .data(event));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stream agent event", e);
        }
    }

    private AgentRunOptions optionsFrom(ChatRunRequest request) {
        return AgentRunOptions.builder()
                .traceId(request.getTraceId())
                .attributes(request.getAttributes())
                .build();
    }

    private void sendAgentError(SseEmitter emitter, String sessionId, Exception exception) {
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("message", exception.getMessage());
            AgentEvent event = AgentEvent.of(sessionId, null, "agent_error", toJson(payload));
            emitter.send(SseEmitter.event()
                    .id(event.getEventId())
                    .name(event.getType())
                    .data(event));
        } catch (Exception ignored) {
        }
    }

    private String toJson(Map<String, Object> payload) {
        String message = String.valueOf(payload.get("message"));
        return "{\"message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}

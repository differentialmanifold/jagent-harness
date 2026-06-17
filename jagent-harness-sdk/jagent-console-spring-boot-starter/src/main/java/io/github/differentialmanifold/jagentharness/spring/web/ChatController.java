package io.github.differentialmanifold.jagentharness.spring.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentHarness;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopCoordinator;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopHandle;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestResult;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalDecision;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalHandler;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalMode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatApprovalRequest;
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
    private final ToolApprovalCoordinator toolApprovalCoordinator;
    private final ObjectMapper objectMapper;

    public ChatController(SessionManager sessionManager,
                          AgentHarness agentHarness,
                          TaskExecutor agentTaskExecutor,
                          RunStopCoordinator runStopCoordinator,
                          ToolApprovalCoordinator toolApprovalCoordinator,
                          ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.agentHarness = agentHarness;
        this.agentTaskExecutor = agentTaskExecutor;
        this.runStopCoordinator = runStopCoordinator;
        this.toolApprovalCoordinator = toolApprovalCoordinator;
        this.objectMapper = objectMapper;
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
                            .approvalMode(approvalMode(effectiveRequest))
                            .approvalHandler(approvalHandler(effectiveRequest, requestId, sessionId, emitter))
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
                    toolApprovalCoordinator.cancelRequest(requestId);
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

    @PostMapping("/approvals/resolve")
    public ResponseEntity<Void> resolveApproval(@RequestBody ChatApprovalRequest request) {
        String requestId = request == null ? null : request.getRequestId();
        String approvalId = request == null ? null : request.getApprovalId();
        boolean resolved = toolApprovalCoordinator.resolve(
                requireRequestId(requestId),
                requireApprovalId(approvalId),
                request != null && request.isApproved(),
                request == null ? null : request.getReason());
        if (!resolved) {
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

    private String requireApprovalId(String approvalId) {
        String value = approvalId == null ? "" : approvalId.trim();
        if (value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException("approvalId must contain 1-128 characters");
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

    private ToolApprovalMode approvalMode(ChatRunRequest request) {
        String mode = request.getApprovalMode() == null ? "" : request.getApprovalMode().trim();
        if ("ask".equalsIgnoreCase(mode)
                || "ask_for_approval".equalsIgnoreCase(mode)
                || "ask_approval".equalsIgnoreCase(mode)) {
            return ToolApprovalMode.ASK_FOR_APPROVAL;
        }
        return ToolApprovalMode.FULL_ACCESS;
    }

    private ToolApprovalHandler approvalHandler(ChatRunRequest request,
                                                String requestId,
                                                String sessionId,
                                                SseEmitter emitter) {
        if (approvalMode(request) != ToolApprovalMode.ASK_FOR_APPROVAL) {
            return null;
        }
        return (approvalRequest, stopSignal) -> requestApproval(
                requestId,
                sessionId,
                emitter,
                approvalRequest,
                stopSignal);
    }

    private ToolApprovalDecision requestApproval(String requestId,
                                                 String sessionId,
                                                 SseEmitter emitter,
                                                 ToolApprovalRequest approvalRequest,
                                                 StopSignal stopSignal) throws Exception {
        ToolApprovalDecision decision = toolApprovalCoordinator.awaitDecision(
                requestId,
                approvalRequest,
                stopSignal,
                () -> sendApprovalEvent(
                        emitter,
                        AgentEvent.TOOL_APPROVAL_REQUESTED,
                        requestId,
                        sessionId,
                        approvalRequest,
                        null));
        sendApprovalEvent(emitter, AgentEvent.TOOL_APPROVAL_RESOLVED, requestId, sessionId, approvalRequest, decision);
        return decision;
    }

    private void sendApprovalEvent(SseEmitter emitter,
                                   String type,
                                   String requestId,
                                   String sessionId,
                                   ToolApprovalRequest approvalRequest,
                                   ToolApprovalDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("requestId", requestId);
        payload.put("approvalId", approvalRequest.getApprovalId());
        payload.put("toolCallId", approvalRequest.getToolCallId());
        payload.put("toolName", approvalRequest.getToolName());
        payload.put("title", approvalRequest.getTitle());
        payload.put("message", approvalRequest.getMessage());
        payload.put("action", approvalRequest.getAction());
        payload.put("target", approvalRequest.getTarget());
        payload.put("metadata", approvalRequest.getMetadata());
        if (decision != null) {
            payload.put("approved", decision.isApproved());
            payload.put("reason", decision.getReason());
        }
        sendEvent(emitter, AgentEvent.of(sessionId, null, type, toJson(payload)));
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
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }
    }
}

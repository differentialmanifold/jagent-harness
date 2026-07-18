package io.github.differentialmanifold.jagentharness.spring.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentHarness;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputCoordinator;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputReceipt;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopCoordinator;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopHandle;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestResult;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalCoordinator;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalDecision;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalHandler;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalMode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatApprovalRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatInputRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatInputResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatRunRequest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    static final String RUN_ID_HEADER = "X-Run-Id";

    private final SessionManager sessionManager;
    private final AgentHarness agentHarness;
    private final TaskExecutor agentTaskExecutor;
    private final RunStopCoordinator runStopCoordinator;
    private final RunInputCoordinator runInputCoordinator;
    private final ToolApprovalCoordinator toolApprovalCoordinator;
    private final ObjectMapper objectMapper;

    public ChatController(SessionManager sessionManager,
                          AgentHarness agentHarness,
                          TaskExecutor agentTaskExecutor,
                          RunStopCoordinator runStopCoordinator,
                          RunInputCoordinator runInputCoordinator,
                          ToolApprovalCoordinator toolApprovalCoordinator,
                          ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.agentHarness = agentHarness;
        this.agentTaskExecutor = agentTaskExecutor;
        this.runStopCoordinator = runStopCoordinator;
        this.runInputCoordinator = runInputCoordinator;
        this.toolApprovalCoordinator = toolApprovalCoordinator;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestBody ChatRunRequest request) {
        ChatRunRequest effectiveRequest = requireRunRequest(request);
        String sessionId = effectiveRequest.getSessionId();
        String runId = Ids.newId("run");
        RunStopHandle stopHandle = runStopCoordinator.register(runId, sessionId);
        SseEmitter emitter = new SseEmitter(0L);
        try {
            runInputCoordinator.activateRun(sessionId, runId);
            agentTaskExecutor.execute(() -> {
                try {
                    runRequest(
                            effectiveRequest,
                            runId,
                            stopHandle,
                            emitter);
                } catch (Exception ignored) {
                } finally {
                    try {
                        toolApprovalCoordinator.cancelRun(runId);
                    } finally {
                        try {
                            stopHandle.close();
                        } finally {
                            Thread.interrupted();
                            emitter.complete();
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            runInputCoordinator.closeRun(sessionId, runId);
            stopHandle.close();
            throw e;
        }
        return ResponseEntity.ok()
                .header(RUN_ID_HEADER, runId)
                .body(emitter);
    }

    @PostMapping("/runs/{runId}/messages")
    public ResponseEntity<ChatInputResponse> submitMessage(
            @PathVariable("runId") String runId,
            @RequestBody ChatInputRequest request) {
        ChatInputRequest effectiveRequest = requireInputRequest(request);
        RunInputReceipt receipt = runInputCoordinator.submitInput(
                requireId(runId, "runId"),
                effectiveRequest.getContent(),
                inputId(effectiveRequest));
        return ResponseEntity.accepted().body(toResponse(receipt));
    }

    @PostMapping("/runs/{runId}/stop")
    public ResponseEntity<Void> stop(@PathVariable("runId") String runId) {
        StopRequestResult result = runStopCoordinator.requestStop(requireId(runId, "runId"));
        if (result == StopRequestResult.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/approvals/resolve")
    public ResponseEntity<Void> resolveApproval(@RequestBody ChatApprovalRequest request) {
        String runId = request == null ? null : request.getRunId();
        String approvalId = request == null ? null : request.getApprovalId();
        boolean resolved = toolApprovalCoordinator.resolve(
                requireId(runId, "runId"),
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

    private ChatInputRequest requireInputRequest(ChatInputRequest request) {
        if (request == null || request.getContent() == null
                || request.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("content is required");
        }
        request.setContent(request.getContent().trim());
        return request;
    }

    private String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        return sessionId.trim();
    }

    private String requireApprovalId(String approvalId) {
        String value = approvalId == null ? "" : approvalId.trim();
        if (value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException("approvalId must contain 1-128 characters");
        }
        return value;
    }

    private String requireId(String id, String name) {
        String value = id == null ? "" : id.trim();
        if (value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must contain 1-128 characters");
        }
        return value;
    }

    private String inputId(ChatInputRequest request) {
        String inputId = request.getInputId();
        return inputId == null || inputId.trim().isEmpty()
                ? Ids.newId("input")
                : requireId(inputId, "inputId");
    }

    private ChatInputResponse toResponse(RunInputReceipt receipt) {
        return new ChatInputResponse(
                receipt.getInputId(),
                receipt.getStatus().name());
    }

    private void sendEvent(SseEmitter emitter, AgentEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.getEventId())
                    .name(event.getType())
                    .data(event));
        } catch (IOException | IllegalStateException ignored) {
            // The run lifecycle is independent of a browser disconnect. The stream can still
            // finish, persist its output, and release its active-run state normally.
        }
    }

    private void runRequest(ChatRunRequest request,
                            String runId,
                            RunStopHandle stopHandle,
                            SseEmitter emitter) {
        String sessionId = request.getSessionId();
        try {
            stopHandle.throwIfAborted();
            AgentRunOptions options = optionsFrom(request)
                    .toBuilder()
                    .runId(runId)
                    .eventConsumer(event -> sendEvent(emitter, event))
                    .stopSignal(stopHandle)
                    .runInputSource(runInputCoordinator)
                    .approvalMode(approvalMode(request))
                    .approvalHandler(approvalHandler(
                            request,
                            sessionId,
                            runId,
                            emitter))
                    .build();
            agentHarness.run(sessionId, request.getContent(), options);
            stopHandle.throwIfAborted();
        } finally {
            runInputCoordinator.closeRun(sessionId, runId);
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
                                                String sessionId,
                                                String runId,
                                                SseEmitter emitter) {
        if (approvalMode(request) != ToolApprovalMode.ASK_FOR_APPROVAL) {
            return null;
        }
        return (approvalRequest, stopSignal) -> requestApproval(
                sessionId,
                runId,
                emitter,
                approvalRequest,
                stopSignal);
    }

    private ToolApprovalDecision requestApproval(String sessionId,
                                                 String runId,
                                                 SseEmitter emitter,
                                                 ToolApprovalRequest approvalRequest,
                                                 StopSignal stopSignal) throws Exception {
        ToolApprovalDecision decision = toolApprovalCoordinator.awaitDecision(
                runId,
                sessionId,
                approvalRequest,
                stopSignal,
                () -> sendApprovalEvent(
                        emitter,
                        AgentEvent.TOOL_APPROVAL_REQUESTED,
                        sessionId,
                        runId,
                        approvalRequest,
                        null));
        sendApprovalEvent(
                emitter,
                AgentEvent.TOOL_APPROVAL_RESOLVED,
                sessionId,
                runId,
                approvalRequest,
                decision);
        return decision;
    }

    private void sendApprovalEvent(SseEmitter emitter,
                                   String type,
                                   String sessionId,
                                   String runId,
                                   ToolApprovalRequest approvalRequest,
                                   ToolApprovalDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("runId", runId);
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
        sendEvent(emitter, AgentEvent.of(sessionId, runId, null, type, toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }
    }
}

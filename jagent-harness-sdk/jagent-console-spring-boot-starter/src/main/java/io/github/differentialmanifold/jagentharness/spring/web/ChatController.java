package io.github.differentialmanifold.jagentharness.spring.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalCoordinator;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalDecision;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalHandler;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalMode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatApprovalRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatInputRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatInputResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatImageRequest;
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
    static final int MAX_IMAGES = 4;
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    static final int MAX_TOTAL_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_IMAGE_NAME_LENGTH = 255;

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
                toMessageImages(effectiveRequest.getImages()),
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
            throw new IllegalArgumentException("sessionId and message content are required");
        }
        request.setSessionId(requireSessionId(request.getSessionId()));
        request.setImages(requireImages(request.getImages()));
        String content = request.getContent() == null ? "" : request.getContent();
        if (content.trim().isEmpty()) {
            content = "";
        }
        request.setContent(content);
        if (content.isEmpty() && request.getImages().isEmpty()) {
            throw new IllegalArgumentException("text or at least one image is required");
        }
        sessionManager.requireSession(request.getSessionId());
        return request;
    }

    private ChatInputRequest requireInputRequest(ChatInputRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("text or at least one image is required");
        }
        request.setImages(requireImages(request.getImages()));
        request.setContent(request.getContent() == null ? "" : request.getContent().trim());
        if (request.getContent().isEmpty() && request.getImages().isEmpty()) {
            throw new IllegalArgumentException("text or at least one image is required");
        }
        return request;
    }

    private List<ChatImageRequest> requireImages(List<ChatImageRequest> images) {
        if (images == null || images.isEmpty()) {
            return Collections.emptyList();
        }
        if (images.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("A message can contain at most " + MAX_IMAGES + " images");
        }

        long totalBytes = 0L;
        List<ChatImageRequest> normalized = new ArrayList<ChatImageRequest>(images.size());
        for (int index = 0; index < images.size(); index++) {
            ChatImageRequest image = images.get(index);
            if (image == null) {
                throw new IllegalArgumentException("Image " + (index + 1) + " is missing");
            }
            String url = image.getUrl() == null ? "" : image.getUrl().trim();
            String mediaType = dataUrlMediaType(url);
            if (mediaType == null) {
                throw new IllegalArgumentException(
                        "Images must be PNG, JPEG, WebP, or GIF base64 data URLs");
            }
            String declaredMediaType = image.getMediaType() == null
                    ? ""
                    : image.getMediaType().trim().toLowerCase(Locale.ROOT);
            if (!declaredMediaType.isEmpty() && !mediaType.equals(declaredMediaType)) {
                throw new IllegalArgumentException("Image mediaType does not match its data URL");
            }
            int comma = url.indexOf(',');
            String encoded = url.substring(comma + 1);
            int maxEncodedLength = ((MAX_IMAGE_BYTES + 2) / 3) * 4;
            if (encoded.isEmpty() || encoded.length() > maxEncodedLength) {
                throw new IllegalArgumentException("Each image must be at most 10 MB");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Image data is not valid base64");
            }
            int decodedBytes = decoded.length;
            if (decodedBytes == 0 || decodedBytes > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Each image must be at most 10 MB");
            }
            if (!hasImageSignature(mediaType, decoded)) {
                throw new IllegalArgumentException(
                        "Image data does not match its declared PNG, JPEG, WebP, or GIF format");
            }
            totalBytes += decodedBytes;
            if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                throw new IllegalArgumentException("Images in one message must total at most 20 MB");
            }

            ChatImageRequest accepted = new ChatImageRequest();
            accepted.setName(normalizedImageName(image.getName(), mediaType, index));
            accepted.setMediaType(mediaType);
            accepted.setUrl(url);
            accepted.setDetail(normalizedImageDetail(image.getDetail()));
            normalized.add(accepted);
        }
        return normalized;
    }

    private String dataUrlMediaType(String url) {
        String value = url == null ? "" : url;
        String[] supported = new String[]{"image/png", "image/jpeg", "image/webp", "image/gif"};
        for (String mediaType : supported) {
            String prefix = "data:" + mediaType + ";base64,";
            if (value.length() >= prefix.length()
                    && value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return mediaType;
            }
        }
        return null;
    }

    private boolean hasImageSignature(String mediaType, byte[] data) {
        if ("image/png".equals(mediaType)) {
            return startsWith(data, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
        }
        if ("image/jpeg".equals(mediaType)) {
            return startsWith(data, 0xff, 0xd8, 0xff);
        }
        if ("image/gif".equals(mediaType)) {
            return startsWith(data, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
                    || startsWith(data, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61);
        }
        return "image/webp".equals(mediaType)
                && startsWith(data, 0x52, 0x49, 0x46, 0x46)
                && startsWithAt(data, 8, 0x57, 0x45, 0x42, 0x50);
    }

    private boolean startsWith(byte[] data, int... signature) {
        return startsWithAt(data, 0, signature);
    }

    private boolean startsWithAt(byte[] data, int offset, int... signature) {
        if (data == null || offset < 0 || data.length - offset < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if ((data[offset + index] & 0xff) != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private String normalizedImageName(String name, String mediaType, int index) {
        String value = name == null ? "" : name.trim();
        if (value.length() > MAX_IMAGE_NAME_LENGTH) {
            throw new IllegalArgumentException("Image names must contain at most 255 characters");
        }
        if (!value.isEmpty()) {
            return value;
        }
        String extension = "image/jpeg".equals(mediaType) ? "jpg" : mediaType.substring("image/".length());
        return "image-" + (index + 1) + "." + extension;
    }

    private String normalizedImageDetail(String detail) {
        String value = detail == null ? "" : detail.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        if (!"auto".equals(value) && !"low".equals(value) && !"high".equals(value)) {
            throw new IllegalArgumentException("Image detail must be auto, low, or high");
        }
        return value;
    }

    private List<MessageImage> toMessageImages(List<ChatImageRequest> images) {
        if (images == null || images.isEmpty()) {
            return Collections.emptyList();
        }
        List<MessageImage> result = new ArrayList<MessageImage>(images.size());
        for (ChatImageRequest image : images) {
            result.add(new MessageImage(
                    image.getName(),
                    image.getMediaType(),
                    image.getUrl(),
                    image.getDetail()));
        }
        return result;
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
            agentHarness.run(
                    sessionId,
                    request.getContent(),
                    toMessageImages(request.getImages()),
                    options);
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

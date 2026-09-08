package io.github.differentialmanifold.jagentharness.example.coding.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.client.*;
import io.github.differentialmanifold.jagentharness.example.coding.approval.*;
import io.github.differentialmanifold.jagentharness.example.coding.execution.*;
import io.github.differentialmanifold.jagentharness.example.coding.tool.CodingTool;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import javax.servlet.http.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Hosts the shared console and owns local tool execution. Only this process calls the agent server.
 */
@RestController
public class CodingGateway {
    private final ObjectMapper json;
    private final String server, token, instructions;
    private final CodingWorkspaceService workspaces;
    private final AgentClient client;
    private final List<CodingTool> localTools;
    private final ConcurrentMap<String, Running> active = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            new ThreadPoolExecutor(
                    2,
                    16,
                    60,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    new ThreadPoolExecutor.AbortPolicy());

    public CodingGateway(
            ObjectMapper json,
            List<CodingTool> tools,
            CodingWorkspaceService workspaces,
            @Qualifier("codingInstructions") String instructions,
            @Value("${coding.server-url}") String server,
            @Value("${coding.token:}") String token)
            throws IOException {
        this.json = json;
        this.localTools = tools;
        this.workspaces = workspaces;
        this.instructions = instructions;
        this.server = server.replaceAll("/$", "");
        this.token = token;
        client = new AgentClient(new HttpAgentTransport(this.server, token), tools);
    }

    @PostMapping(value = "/api/v1/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Tool-Approval-Mode", defaultValue = "ask_approval")
                    String approvalMode)
            throws IOException {
        String session = Objects.toString(request.sessionId, "");
        if (request.messages.size() != 1 || !"user".equals(request.messages.get(0).role))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Coding UI starts a user turn; SDK owns tool-result exchanges");
        Map<String, Object> detail =
                json.readValue(
                        forward(
                                        "POST",
                                        "/api/v1/sessions/detail",
                                        json.writeValueAsBytes(
                                                Collections.singletonMap("sessionId", session)))
                                .body,
                        Map.class);
        Map<String, Object> record = (Map<String, Object>) detail.get("session");
        if (record == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session not found");
        Running run = new Running();
        run.session = session;
        run.id =
                request.runId == null
                        ? "run_" + UUID.randomUUID().toString().replace("-", "")
                        : request.runId;
        run.workspace = workspaces.workspaceRoot(Objects.toString(record.get("workspacePath"), ""));
        run.ask = !"full_access".equals(approvalMode);
        run.emitter = new SseEmitter(0L);
        request.sessionId = session;
        request.runId = run.id;
        request.stream = true;
        request.clientInstructions = instructions;
        CodingContext context =
                new CodingContext(
                        run.workspace,
                        run.signal,
                        run.ask ? ToolApprovalMode.ASK_FOR_APPROVAL : ToolApprovalMode.FULL_ACCESS,
                        (approval, stop) -> approve(run, approval),
                        null,
                        null);
        if (active.putIfAbsent(run.id, run) != null)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Run is already active in this client");
        try {
            executor.execute(
                    () -> {
                        try {
                            ChatResponse result =
                                    client.run(
                                            request,
                                            new ClientRunOptions()
                                                    .onEvent(
                                                            event -> {
                                                                try {
                                                                    run.emitter.send(
                                                                            SseEmitter.event()
                                                                                    .id(
                                                                                            event.eventId)
                                                                                    .name(
                                                                                            event.type)
                                                                                    .data(event));
                                                                } catch (Exception ignored) {
                                                                }
                                                            })
                                                    .stopSignal(run.signal)
                                                    .toolContext(context));
                            run.emitter.send(SseEmitter.event().name("response").data(result));
                        } catch (Exception error) {
                            try {
                                Map<String, Object> failure = new LinkedHashMap<>();
                                failure.put(
                                        "status",
                                        error instanceof AgentHttpException
                                                ? ((AgentHttpException) error).status
                                                : 500);
                                failure.put("code", "CLIENT_EXECUTION_ERROR");
                                failure.put(
                                        "message",
                                        error.getMessage() == null
                                                ? error.toString()
                                                : error.getMessage());
                                run.emitter.send(SseEmitter.event().name("error").data(failure));
                            } catch (Exception ignored) {
                            }
                        } finally {
                            active.remove(run.id);
                            run.signal.stop();
                            run.emitter.complete();
                        }
                    });
        } catch (RejectedExecutionException error) {
            active.remove(run.id);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Coding client is at capacity");
        }
        return ResponseEntity.ok().header("X-Run-Id", run.id).body(run.emitter);
    }

    @PostMapping("/api/v1/runs/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id) throws IOException {
        Running run = active.get(id);
        if (run != null) run.signal.stop();
        Forward result = forward("POST", "/api/v1/runs/" + id + "/stop", new byte[0]);
        if (result.status >= 400 && result.status != 404)
            throw new ResponseStatusException(
                    HttpStatus.valueOf(result.status),
                    new String(result.body, StandardCharsets.UTF_8));
        return Collections.singletonMap("accepted", true);
    }

    @PostMapping("/api/v1/approvals/resolve")
    public Map<String, Object> decision(@RequestBody Map<String, Object> body) {
        Running run = active.get(Objects.toString(body.get("runId"), ""));
        String id = Objects.toString(body.get("approvalId"), "");
        CompletableFuture<Boolean> future = run == null ? null : run.approvals.get(id);
        if (future == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval not found");
        future.complete(Boolean.TRUE.equals(body.get("approved")));
        return Collections.singletonMap("accepted", true);
    }

    private ToolApprovalDecision approve(Running run, ToolApprovalRequest request)
            throws Exception {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        run.approvals.put(request.getApprovalId(), future);
        Map<String, Object> payload =
                json.convertValue(request, new TypeReference<Map<String, Object>>() {});
        payload.put("runId", run.id);
        payload.put("status", "pending");
        emit(run, "tool_approval_requested", payload, null);
        try (StopRegistration registration = run.signal.onStop(() -> future.cancel(false))) {
            boolean approved = future.get(10, TimeUnit.MINUTES);
            payload.put("approved", approved);
            payload.put("status", "resolved");
            emit(run, "tool_approval_resolved", payload, null);
            return approved
                    ? ToolApprovalDecision.approved("Approved by user")
                    : ToolApprovalDecision.denied("Denied by user");
        } finally {
            run.approvals.remove(request.getApprovalId());
        }
    }

    @GetMapping("/api/v1/tools")
    public List<Map<String, Object>> tools() throws IOException {
        List<Map<String, Object>> result =
                json.readValue(
                        forward("GET", "/api/v1/tools", null).body,
                        new TypeReference<List<Map<String, Object>>>() {});
        for (CodingTool tool : localTools) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", tool.getName());
            t.put("description", tool.getDescription());
            t.put("parameters", tool.getParametersSchema());
            t.put("executionLocation", "CLIENT");
            result.add(t);
        }
        return result;
    }

    @RequestMapping("/api/v1/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] body = read(request.getInputStream(), 32 * 1024 * 1024);
        if ("POST".equals(request.getMethod())
                && "/api/v1/sessions".equals(request.getRequestURI())
                && body.length > 0) {
            JsonNode input = json.readTree(body);
            if (input.hasNonNull("workspacePath"))
                workspaces.workspaceRoot(input.path("workspacePath").asText());
        }
        if ("POST".equals(request.getMethod())
                && Arrays.asList("/api/v1/agent/context", "/api/v1/agent/prompt-preview")
                        .contains(request.getRequestURI())) {
            com.fasterxml.jackson.databind.node.ObjectNode preview =
                    body.length == 0
                            ? json.createObjectNode()
                            : (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(body);
            List<ToolDescriptor> specs = new ArrayList<>();
            for (CodingTool tool : localTools) specs.add(tool.descriptor());
            preview.set("clientTools", json.valueToTree(specs));
            preview.put("clientInstructions", instructions);
            body = json.writeValueAsBytes(preview);
        }
        String uri =
                request.getRequestURI()
                        + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        Forward remote = forward(request.getMethod(), uri, body, request.getContentType());
        response.setStatus(remote.status);
        for (Map.Entry<String, List<String>> h : remote.headers.entrySet())
            if (h.getKey() != null
                    && Arrays.asList("content-type", "content-disposition")
                            .contains(h.getKey().toLowerCase(Locale.ROOT)))
                response.setHeader(h.getKey(), h.getValue().get(0));
        response.getOutputStream().write(remote.body);
    }

    private Forward forward(String method, String path, byte[] bytes) throws IOException {
        return forward(method, path, bytes, "application/json");
    }

    private Forward forward(String method, String path, byte[] bytes, String contentType)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(180000);
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType);
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (bytes != null && bytes.length > 0) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            Forward r = new Forward();
            r.status = connection.getResponseCode();
            r.headers = connection.getHeaderFields();
            r.body =
                    read(
                            r.status >= 400
                                    ? connection.getErrorStream()
                                    : connection.getInputStream(),
                            64 * 1024 * 1024);
            return r;
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] read(InputStream in, int limit) throws IOException {
        if (in == null) return new byte[0];
        try (InputStream source = in;
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = source.read(b)) != -1) {
                if (out.size() + n > limit)
                    throw new IOException("Request/response body exceeds limit");
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void emit(Running run, String type, Object payload, String turnId) {
        try {
            StreamEvent e = new StreamEvent();
            e.eventId = "client_" + UUID.randomUUID();
            e.sessionId = run.session;
            e.runId = run.id;
            e.turnId = turnId;
            e.type = type;
            e.createdAt = java.time.Instant.now().toString();
            e.payload = json.convertValue(payload, Map.class);
            run.emitter.send(SseEmitter.event().name(type).data(e));
        } catch (Exception ignored) {
        }
    }

    @PreDestroy
    public void close() throws IOException {
        for (Running r : active.values()) r.signal.stop();
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class Forward {
        int status;
        byte[] body;
        Map<String, List<String>> headers;
    }

    private static class Running {
        String id, session;
        Path workspace;
        boolean ask;
        SseEmitter emitter;
        Signal signal = new Signal();
        ConcurrentMap<String, CompletableFuture<Boolean>> approvals = new ConcurrentHashMap<>();
    }

    private static class Signal implements StopSignal {
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        public boolean isAborted() {
            return stopped.get();
        }

        public void throwIfAborted() {
            if (isAborted()) throw new StopRequestedException();
        }

        public StopRegistration onStop(Runnable listener) {
            listeners.add(listener);
            if (isAborted()) listener.run();
            return () -> listeners.remove(listener);
        }

        void stop() {
            if (stopped.compareAndSet(false, true)) for (Runnable r : listeners) r.run();
        }
    }
}

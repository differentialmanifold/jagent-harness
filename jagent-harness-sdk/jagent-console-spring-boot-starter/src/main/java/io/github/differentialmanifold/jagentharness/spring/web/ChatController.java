package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class ChatController {
    private final ChatService service;
    private final io.github.differentialmanifold.jagentharness.core.agent.RunInputCoordinator
            inputs;
    private final ExecutorService tasks =
            new ThreadPoolExecutor(
                    2,
                    32,
                    60,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    new ThreadPoolExecutor.AbortPolicy());

    public ChatController(
            ChatService service,
            io.github.differentialmanifold.jagentharness.core.agent.RunInputCoordinator inputs) {
        this.service = service;
        this.inputs = inputs;
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return service.chat(request, null);
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            tasks.execute(
                    () -> {
                        try {
                            ChatResponse response =
                                    service.chat(
                                            request, event -> send(emitter, event.type, event));
                            send(emitter, "response", response);
                            emitter.complete();
                        } catch (Exception error) {
                            send(emitter, "error", errorBody(error));
                            emitter.complete();
                        }
                    });
        } catch (RejectedExecutionException error) {
            throw new ProtocolException(503, "CAPACITY", "Agent server is at capacity");
        }
        return emitter;
    }

    @GetMapping("/sessions/{id}/state")
    public ChatResponse state(@PathVariable String id) {
        return service.state(id);
    }

    @PostMapping("/runs/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        service.cancel(id);
        return Collections.singletonMap("accepted", true);
    }

    @PostMapping("/runs/{id}/inputs")
    public io.github.differentialmanifold.jagentharness.spring.web.dto.ChatInputResponse input(
            @PathVariable String id,
            @RequestBody
                    io.github.differentialmanifold.jagentharness.spring.web.dto.ChatInputRequest
                            request) {
        java.util.List<io.github.differentialmanifold.jagentharness.core.message.MessageImage>
                images = new java.util.ArrayList<>();
        for (io.github.differentialmanifold.jagentharness.spring.web.dto.ChatImageRequest image :
                io.github.differentialmanifold.jagentharness.spring.web.ImageInputValidator
                        .normalize(request.getImages()))
            images.add(
                    new io.github.differentialmanifold.jagentharness.core.message.MessageImage(
                            image.getName(),
                            image.getMediaType(),
                            image.getUrl(),
                            image.getDetail()));
        io.github.differentialmanifold.jagentharness.core.agent.RunInputReceipt receipt =
                inputs.submitInput(id, request.getContent(), images, request.getInputId());
        return new io.github.differentialmanifold.jagentharness.spring.web.dto.ChatInputResponse(
                receipt.getInputId(), receipt.getStatus().name());
    }

    private Map<String, Object> errorBody(Exception error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", error.getMessage() == null ? error.toString() : error.getMessage());
        body.put(
                "code",
                error instanceof ProtocolException
                        ? ((ProtocolException) error).code
                        : "INTERNAL_ERROR");
        body.put(
                "status",
                error instanceof ProtocolException ? ((ProtocolException) error).status : 500);
        return body;
    }

    private void send(SseEmitter emitter, String type, Object event) {
        try {
            emitter.send(SseEmitter.event().name(type).data(event));
        } catch (Exception ignored) {
            /* A lost response is not automatically replayed. The caller inspects session history. */
        }
    }

    @PreDestroy
    public void close() {
        tasks.shutdownNow();
    }
}

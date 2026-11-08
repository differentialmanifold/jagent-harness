package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.agent.RunInputReceipt;
import io.github.differentialmanifold.jagentharness.core.execution.RunService;
import io.github.differentialmanifold.jagentharness.core.message.ImageInputValidator;
import io.github.differentialmanifold.jagentharness.protocol.*;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatInputRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatInputResponse;
import java.util.*;
import javax.annotation.PreDestroy;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class ChatController {
    private final RunService service;
    private final HttpExecution http = new HttpExecution();

    public ChatController(RunService service) {
        this.service = service;
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<ChatResponse> chat(@RequestBody ChatSubmission request) {
        return HttpExecution.result(service.submit(request, null).result());
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatSubmission request) {
        return http.stream(events -> service.submit(request, events));
    }

    @GetMapping("/sessions/{id}/state")
    public ChatResponse state(@PathVariable String id) {
        return service.state(id);
    }

    @PostMapping("/runs/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        service.stop(id);
        return Collections.singletonMap("accepted", true);
    }

    @PostMapping("/runs/{id}/inputs")
    public ChatInputResponse input(@PathVariable String id, @RequestBody ChatInputRequest request) {
        RunInputReceipt receipt =
                service.input(
                        id,
                        request.getContent(),
                        request.getInputId(),
                        ImageInputValidator.normalize(request.getImages()));
        return new ChatInputResponse(receipt.getInputId(), receipt.getStatus().name());
    }

    @PreDestroy
    public void close() {
        http.close();
    }
}

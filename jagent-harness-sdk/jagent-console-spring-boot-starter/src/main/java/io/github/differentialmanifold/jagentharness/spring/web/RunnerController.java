package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/runners")
public class RunnerController {
    private final RunnerService service;

    public RunnerController(RunnerService service) {
        this.service = service;
    }

    @GetMapping
    public List<RunnerInfo> list() {
        return service.list();
    }

    @PostMapping
    public RunnerInfo register(
            @RequestBody RunnerInfo body,
            @RequestHeader(value = "X-Runner-Secret", required = false) String secret) {
        return service.register(body, secret);
    }

    @GetMapping("/{id}/poll")
    public DeferredResult<RunnerPoll> poll(
            @PathVariable String id,
            @RequestHeader(value = "X-Runner-Secret", required = false) String secret,
            @RequestParam(required = false) String activeRun) {
        service.authenticate(id, secret);
        return service.poll(id, activeRun);
    }

    @PostMapping(value = "/chat", produces = "text/event-stream")
    public SseEmitter submit(@RequestBody ChatRequest request) {
        return service.submit(request);
    }

    @GetMapping("/runs/{run}")
    public RunnerJob job(@PathVariable String run) {
        return service.job(run);
    }

    @GetMapping(value = "/runs/{run}/events", produces = "text/event-stream")
    public SseEmitter watch(@PathVariable String run) {
        service.job(run);
        return service.watch(run);
    }

    @PostMapping("/{id}/runs/{run}/recover")
    public RunnerJob recover(
            @PathVariable String id,
            @PathVariable String run,
            @RequestHeader(value = "X-Runner-Secret", required = false) String secret) {
        service.authenticate(id, secret);
        return service.recover(id, run);
    }

    @PostMapping("/{id}/complete")
    public Map<String, Boolean> complete(
            @PathVariable String id,
            @RequestHeader(value = "X-Runner-Secret", required = false) String secret,
            @RequestBody ChatResponse response) {
        service.authenticate(id, secret);
        service.complete(id, response);
        return Collections.singletonMap("accepted", true);
    }

    @PostMapping("/{id}/events")
    public Map<String, Boolean> event(
            @PathVariable String id,
            @RequestHeader(value = "X-Runner-Secret", required = false) String secret,
            @RequestBody StreamEvent event) {
        service.authenticate(id, secret);
        service.localEvent(id, event);
        return Collections.singletonMap("accepted", true);
    }
}

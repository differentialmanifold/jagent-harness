package io.github.differentialmanifold.jagentharness.spring.personal;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/personal/agents/{agent}/feishu")
public class FeishuController {
    private final FeishuService service;

    public FeishuController(FeishuService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> status(@PathVariable String agent) {
        return service.status(agent);
    }

    @PostMapping("/register")
    public Map<String, Object> register(@PathVariable String agent) {
        return service.register(agent);
    }

    @PostMapping("/connect")
    public Map<String, Object> connect(
            @PathVariable String agent, @RequestBody Map<String, String> body) {
        FeishuService.Configuration config = new FeishuService.Configuration();
        config.appId = body.get("appId");
        config.appSecret = body.get("appSecret");
        config.brand = body.getOrDefault("brand", "feishu");
        return service.connect(agent, config);
    }

    @PostMapping("/reconnect")
    public Map<String, Object> reconnect(@PathVariable String agent) {
        return service.reconnect(agent);
    }

    @PostMapping("/disconnect")
    public void disconnect(@PathVariable String agent) {
        service.disconnect(agent);
    }

    @PostMapping("/retry")
    public void retry(@PathVariable String agent, @RequestBody Map<String, String> body) {
        service.retry(agent, body.get("messageId"));
    }
}

package io.github.differentialmanifold.jagentharness.spring.web;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.spring.HarnessProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HarnessProperties properties;

    public HealthController(HarnessProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "ok");
        result.put("model", properties.getModel().getModel());
        result.put("provider", properties.getModel().getProvider());
        result.put("store", "jdbc");
        return result;
    }
}

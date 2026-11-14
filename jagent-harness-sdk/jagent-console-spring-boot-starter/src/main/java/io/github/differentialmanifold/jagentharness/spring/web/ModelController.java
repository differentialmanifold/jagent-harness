package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.model.*;
import io.github.differentialmanifold.jagentharness.core.model.ModelCatalogStore.Configuration;
import io.github.differentialmanifold.jagentharness.core.provider.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/models")
public class ModelController {
    private final ModelCatalog catalog;

    public ModelController(ModelCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public Configuration list() {
        return catalog.publicConfiguration();
    }

    @PostMapping
    public Configuration save(@RequestBody Configuration settings) {
        return catalog.save(settings);
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id) {
        ModelProfile model = catalog.require(id);
        ModelRequest request = new ModelRequest();
        request.setModel(model.model);
        request.setSystemPrompt("Reply briefly to confirm this connection works.");
        request.setMessages(
                Collections.singletonList(AgentMessage.user("connection-test", "Hello")));
        request.setTools(Collections.emptyList());
        ModelResponse response = catalog.provider(model).chat(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelId", id);
        out.put("reply", response.getContent());
        return out;
    }
}

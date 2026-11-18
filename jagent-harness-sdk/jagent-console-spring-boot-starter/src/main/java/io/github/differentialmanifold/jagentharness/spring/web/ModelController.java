package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.model.*;
import io.github.differentialmanifold.jagentharness.core.model.ModelCatalogStore.Configuration;
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
        return catalog.testConnection(id);
    }
}

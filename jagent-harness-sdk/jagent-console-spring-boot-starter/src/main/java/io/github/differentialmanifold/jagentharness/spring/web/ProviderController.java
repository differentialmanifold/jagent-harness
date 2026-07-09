package io.github.differentialmanifold.jagentharness.spring.web;

import java.util.ArrayList;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.spring.HarnessProperties;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ProviderListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ModelProviderRegistry providerRegistry;
    private final HarnessProperties properties;

    public ProviderController(ModelProviderRegistry providerRegistry,
                              HarnessProperties properties) {
        this.providerRegistry = providerRegistry;
        this.properties = properties;
    }

    @GetMapping
    public ProviderListResponse list() {
        List<String> names = new ArrayList<String>();
        for (ModelProvider provider : providerRegistry.all()) {
            names.add(provider.getName());
        }
        ProviderListResponse result = new ProviderListResponse();
        result.setProviders(names);
        result.setActiveProvider(properties.getModel().getProvider());
        result.setModel(properties.getModel().getModel());
        result.setBaseUrl(properties.getModel().getBaseUrl());
        result.setContextWindowTokens(properties.getModel().getContextWindowTokens());
        result.setApiKeyConfigured(properties.getModel().getApiKey() != null
                && !properties.getModel().getApiKey().trim().isEmpty());
        return result;
    }
}

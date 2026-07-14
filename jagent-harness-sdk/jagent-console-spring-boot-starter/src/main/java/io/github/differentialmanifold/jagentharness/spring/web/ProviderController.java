package io.github.differentialmanifold.jagentharness.spring.web;

import java.util.ArrayList;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.spring.HarnessProperties;
import io.github.differentialmanifold.jagentharness.spring.ModelAccessTokenProvider;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ProviderListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ModelProviderRegistry providerRegistry;
    private final HarnessProperties properties;
    private final ModelAccessTokenProvider accessTokenProvider;

    public ProviderController(ModelProviderRegistry providerRegistry,
                              HarnessProperties properties,
                              ModelAccessTokenProvider accessTokenProvider) {
        this.providerRegistry = providerRegistry;
        this.properties = properties;
        this.accessTokenProvider = accessTokenProvider;
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
        result.setApiKeyConfigured(isAccessTokenConfigured());
        return result;
    }

    private boolean isAccessTokenConfigured() {
        if (accessTokenProvider == null) {
            return false;
        }
        try {
            String accessToken = accessTokenProvider.getAccessToken();
            return accessToken != null && !accessToken.trim().isEmpty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}

package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.spring.HarnessProperties;
import io.github.differentialmanifold.jagentharness.spring.ModelAccessTokenProvider;
import org.junit.jupiter.api.Test;

class ProviderControllerTest {

    @Test
    void reportsTokenProvidedByCustomImplementation() {
        ProviderController controller = controller(() -> "custom-token");

        assertTrue(controller.list().isApiKeyConfigured());
    }

    @Test
    void reportsMissingOrUnavailableTokenAsNotConfigured() {
        assertFalse(controller(() -> "  ").list().isApiKeyConfigured());
        assertFalse(controller(() -> {
            throw new IllegalStateException("token unavailable");
        }).list().isApiKeyConfigured());
    }

    private ProviderController controller(ModelAccessTokenProvider accessTokenProvider) {
        return new ProviderController(
                new ModelProviderRegistry(),
                new HarnessProperties(),
                accessTokenProvider);
    }
}

package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.model.*;
import io.github.differentialmanifold.jagentharness.core.provider.*;
import io.github.differentialmanifold.jagentharness.spring.*;
import org.junit.jupiter.api.Test;

class ModelConfigurationTest {
    @Test
    void environmentValuesAreEditableWithoutExposingCredentialsAndSavedValuesWin() {
        HarnessProperties properties = new HarnessProperties();
        properties.getModel().setModel("environment-model");
        properties.getModel().setBaseUrl("https://environment.example/v1");
        properties.getModel().setApiKey("environment-secret");
        properties.getModel().setTemperature(0.7);
        properties.getModel().setTimeoutSeconds(90);
        properties.getModel().setStreamEnabled(false);
        ModelProviderRegistry registry = new ModelProviderRegistry();
        ModelProvider registered =
                new ModelProvider() {
                    public String getName() {
                        return "openai-compatible";
                    }

                    public ModelResponse chat(ModelRequest request) {
                        throw new AssertionError("No real model calls");
                    }
                };
        registry.register(registered);
        InMemoryModelCatalogStore store = new InMemoryModelCatalogStore();
        AgentHarnessAutoConfiguration wiring = new AgentHarnessAutoConfiguration();
        ModelCatalog models = wiring.modelCatalog(store, properties, registry, new ObjectMapper());
        ModelCatalogStore.Configuration config = models.publicConfiguration();
        assertEquals("environment", config.source);
        ModelProfile initial = config.models.get(0);
        assertEquals("environment-model", initial.model);
        assertEquals("https://environment.example/v1", initial.baseUrl);
        assertEquals(0.7, initial.temperature);
        assertEquals(90, initial.timeoutSeconds);
        assertFalse(initial.streamEnabled);
        assertEquals("", initial.apiKey);
        assertTrue(initial.credentialsConfigured);
        assertSame(registered, models.provider(models.require(null)));
        initial.model = "saved-model";
        initial.baseUrl = "https://saved.example/v1";
        initial.timeoutSeconds = 60;
        ModelCatalogStore.Configuration saved = models.save(config);
        assertEquals("saved", saved.source);
        assertEquals("", saved.models.get(0).apiKey);
        assertEquals("environment-secret", models.require(null).apiKey);
        assertEquals("environment-model", properties.getModel().getModel());
        properties.getModel().setModel("new-environment-model");
        properties.getModel().setApiKey("new-environment-secret");
        ModelCatalog restarted =
                wiring.modelCatalog(store, properties, registry, new ObjectMapper());
        assertEquals("saved-model", restarted.require(null).model);
        assertEquals("https://saved.example/v1", restarted.require(null).baseUrl);
        assertEquals("environment-secret", restarted.require(null).apiKey);
        assertEquals(60, restarted.require(null).timeoutSeconds);
    }
}

package io.github.differentialmanifold.jagentharness.core.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.message.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModelCatalogTest {
    final ObjectMapper json = new ObjectMapper();

    ModelProfile profile(String id) {
        ModelProfile p = new ModelProfile();
        p.id = id;
        p.name = id;
        p.model = id;
        return p;
    }

    ModelCatalog catalog() {
        return new ModelCatalog(new InMemoryModelCatalogStore(), profile("normal"), p -> null);
    }

    void addVisual(ModelCatalog catalog) {
        ModelCatalogStore.Configuration c = catalog.configuration();
        ModelProfile vision = profile("visual");
        vision.inputTypes.add("image");
        vision.capabilities.add("screen-reading");
        vision.baseUrl = "https://model.example/v1";
        vision.apiKey = "private-secret";
        c.models.add(vision);
        catalog.save(c);
    }

    @Test
    void visualWorkHonorsExplicitChoicesAndNeverChangesTheDefault() {
        ModelCatalog c = catalog();
        assertThrows(IllegalArgumentException.class, () -> c.visionModel(null));
        addVisual(c);
        assertEquals("visual", c.visionModel(null));
        assertEquals("visual", c.visionModel("visual"));
        assertThrows(IllegalArgumentException.class, () -> c.visionModel("normal"));
        assertEquals("normal", c.require(null).id);
        ModelCatalogStore.Configuration cfg = c.publicConfiguration();
        cfg.models.get(1).enabled = false;
        c.save(cfg);
        assertThrows(IllegalArgumentException.class, () -> c.visionModel(null));
    }

    @Test
    void callerSelectionsStayWithinTheirRun() {
        ModelCatalog c = catalog();
        addVisual(c);
        c.begin("s", "one", "visual");
        c.begin("s", "two", null);
        assertEquals("visual", c.begin("s", "one", "normal").model.id);
        assertEquals("normal", c.current("s", "two").model.id);
        assertEquals("normal", c.begin("s", "three", null).model.id);
        assertThrows(IllegalArgumentException.class, () -> c.begin("s", "bad", "missing"));
    }

    @Test
    void resolvesOnlyExactUnambiguousUserRequestedNamesOrIds() {
        ModelCatalog c = catalog();
        addVisual(c);
        ModelCatalogStore.Configuration config = c.publicConfiguration();
        config.models.get(1).name = "Image reader";
        config.models.get(1).model = "vision-2026";
        c.save(config);
        assertNull(c.resolveRequested(null));
        assertNull(c.resolveRequested(" "));
        assertEquals("visual", c.resolveRequested("vision-2026"));
        assertEquals("visual", c.resolveRequested("Image reader"));
        assertThrows(IllegalArgumentException.class, () -> c.resolveRequested("vision"));
        config = c.publicConfiguration();
        ModelProfile duplicate = config.models.get(1).copy();
        duplicate.id = "another-endpoint";
        config.models.add(duplicate);
        c.save(config);
        assertThrows(IllegalArgumentException.class, () -> c.resolveRequested("vision-2026"));
        assertEquals("visual", c.resolveRequested("visual"));
        config = c.publicConfiguration();
        config.models.get(1).enabled = false;
        config.models.get(2).enabled = false;
        c.save(config);
        assertThrows(IllegalArgumentException.class, () -> c.resolveRequested("visual"));
    }

    @Test
    void keepsImageHistoryIntactAndOnlyProjectsUnsupportedInputs() {
        ModelCatalog c = catalog();
        addVisual(c);
        AgentMessage m = AgentMessage.user("s", "Inspect this");
        m.setImages(
                Collections.singletonList(
                        new MessageImage(
                                "screen.png", "image/png", "https://example.com/screen.png")));
        List<AgentMessage> history = Collections.singletonList(m);
        AgentMessage plain = c.readableMessages(c.require("normal"), history).get(0);
        assertTrue(plain.getImages().isEmpty());
        assertTrue(plain.getContent().contains("retained"));
        assertFalse(plain.getContent().contains("Query models"));
        assertFalse(c.prompt("Project", c.require("normal")).contains("Use models"));
        assertEquals(1, m.getImages().size());
        assertEquals("Inspect this", m.getContent());
        assertSame(history, c.readableMessages(c.require("visual"), history));
    }

    @Test
    void masksCredentialsRejectsStaleWritesAndSnapshotsRunningConfiguration() throws Exception {
        ModelCatalog c = catalog();
        addVisual(c);
        assertFalse(json.writeValueAsString(c.publicConfiguration()).contains("private-secret"));
        assertFalse(json.writeValueAsString(c.discover()).contains("model.example"));
        ModelCatalogStore.Configuration old = c.publicConfiguration(),
                next = c.publicConfiguration();
        c.begin("s", "old", "visual");
        next.models.get(1).model = "visual-v2";
        next.defaultModelId = "visual";
        c.save(next);
        assertEquals("private-secret", c.require("visual").apiKey);
        assertEquals("visual", c.current("s", "old").model.model);
        assertEquals("visual-v2", c.begin("s", "new", null).model.model);
        assertThrows(IllegalArgumentException.class, () -> c.save(old));
    }

    @Test
    void individualEditsPreserveOtherModelsDefaultKeysAndRunningSnapshots() throws Exception {
        ModelCatalog c = catalog();
        addVisual(c);
        c.begin("s", "running", "visual");
        ModelProfile added = profile("new-vision");
        added.baseUrl = "https://model.example/v1";
        added.inputTypes.add("image");
        long revision = c.publicConfiguration().revision;
        ModelCatalogStore.Configuration after = c.add(revision, added, "visual");
        assertEquals("normal", after.defaultModelId);
        assertEquals(3, after.models.size());
        assertEquals("private-secret", c.require("new-vision").apiKey);
        assertFalse(json.writeValueAsString(after).contains("private-secret"));
        assertEquals("", added.apiKey, "The caller's object must not receive a stored key");
        ModelProfile edit = after.models.get(2);
        edit.name = "Readable name";
        c.update(after.revision, edit);
        assertEquals("private-secret", c.require("new-vision").apiKey);
        assertEquals("private-secret", c.require("visual").apiKey);
        ModelProfile stale = edit;
        assertThrows(IllegalArgumentException.class, () -> c.update(after.revision, stale));
        assertThrows(
                IllegalArgumentException.class,
                () -> c.remove(c.publicConfiguration().revision, "normal"));
        edit = c.publicConfiguration().models.get(2);
        edit.baseUrl = "https://another.example/v1";
        ModelCatalogStore.Configuration moved = c.update(c.publicConfiguration().revision, edit);
        assertEquals("", c.require("new-vision").apiKey);
        assertFalse(moved.models.get(2).credentialsConfigured);
        ModelProfile stolen = profile("wrong-endpoint");
        stolen.baseUrl = "https://another.example/v1";
        assertThrows(IllegalArgumentException.class, () -> c.add(moved.revision, stolen, "visual"));
        ModelCatalogStore.Configuration selected = c.selectDefault(moved.revision, "new-vision");
        c.remove(selected.revision, "visual");
        assertEquals("visual", c.current("s", "running").model.id);
        assertEquals("new-vision", c.require(null).id);
    }

    @Test
    void fallbackCatalogAlsoIsolatesUsers() {
        ModelCatalog c = catalog();
        try (io.github.differentialmanifold.jagentharness.core.identity.UserContext ignored =
                io.github.differentialmanifold.jagentharness.core.identity.UserContext.enter(
                        "alice")) {
            addVisual(c);
            c.begin("s", "run", "visual");
        }
        try (io.github.differentialmanifold.jagentharness.core.identity.UserContext ignored =
                io.github.differentialmanifold.jagentharness.core.identity.UserContext.enter(
                        "bob")) {
            assertEquals(1, c.publicConfiguration().models.size());
            assertThrows(IllegalArgumentException.class, () -> c.current("s", "run"));
        }
    }

    @Test
    void connectionTestReportsItsActualCoverageAndRedactsProviderResponses() {
        ModelProfile bootstrap = profile("vision");
        bootstrap.apiKey = "connection-secret";
        bootstrap.inputTypes.add("image");
        io.github.differentialmanifold.jagentharness.core.provider.ModelProvider provider =
                new io.github.differentialmanifold.jagentharness.core.provider.ModelProvider() {
                    public String getName() {
                        return "test";
                    }

                    public io.github.differentialmanifold.jagentharness.core.provider.ModelResponse
                            chat(
                                    io.github.differentialmanifold.jagentharness.core.provider
                                                    .ModelRequest
                                            request) {
                        assertEquals("Hello", request.getMessages().get(0).getContent());
                        assertTrue(request.getMessages().get(0).getImages().isEmpty());
                        io.github.differentialmanifold.jagentharness.core.provider.ModelResponse
                                response =
                                        new io.github.differentialmanifold.jagentharness.core
                                                .provider.ModelResponse();
                        response.setContent("Provider replied connection-secret");
                        return response;
                    }
                };
        ModelCatalog c =
                new ModelCatalog(new InMemoryModelCatalogStore(), bootstrap, p -> provider);
        Map<String, Object> result = c.testConnection("vision");
        assertEquals(Collections.singletonList("text"), result.get("testedInputTypes"));
        assertFalse(result.toString().contains("connection-secret"));
        ModelCatalog broken =
                new ModelCatalog(
                        new InMemoryModelCatalogStore(),
                        bootstrap,
                        p -> {
                            throw new IllegalArgumentException("Bad credential connection-secret");
                        });
        assertFalse(
                assertThrows(IllegalArgumentException.class, () -> broken.testConnection("vision"))
                        .getMessage()
                        .contains("connection-secret"));
    }
}

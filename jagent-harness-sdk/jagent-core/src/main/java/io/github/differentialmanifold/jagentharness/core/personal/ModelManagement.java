package io.github.differentialmanifold.jagentharness.core.personal;

import static io.github.differentialmanifold.jagentharness.core.personal.ManagementSchemas.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.model.*;
import java.util.function.Supplier;

/** User-requested model configuration, separate from execution-time model choice. */
public final class ModelManagement {
    private ModelManagement() {}

    public static void register(
            HarnessOperations ops, Supplier<ModelCatalog> catalogs, ObjectMapper json) {
        ObjectNode modelFields =
                fields(
                        "name",
                        text("Display name."),
                        "model",
                        text("Exact provider model name."),
                        "provider",
                        text("Provider name; normally openai-compatible."),
                        "baseUrl",
                        text("HTTP(S) API base URL, without credentials."),
                        "description",
                        text("Model description."),
                        "inputTypes",
                        strings(
                                "Accepted inputs, including text; add image for image input support. Declaring support is not a test."),
                        "capabilities",
                        strings("Optional capability labels."),
                        "contextWindowTokens",
                        property("integer", "Context window size."),
                        "temperature",
                        property("number", "Sampling temperature; null uses the provider default.")
                                .put("nullable", true),
                        "timeoutSeconds",
                        property("integer", "Request timeout, 1–3600 seconds."),
                        "streamEnabled",
                        property("boolean", "Use streaming responses."),
                        "includeUsage",
                        property("boolean", "Request streaming usage."),
                        "enabled",
                        property("boolean", "Whether this model can be selected."));
        ObjectNode addFields = modelFields.deepCopy();
        addFields.set(
                "id", text("New stable ID containing letters, digits, hyphens or underscores."));
        ops.register(
                "models.list",
                "Read model settings and revision. API keys are never returned. Models are configured for this user; environment settings are initial defaults.",
                object(fields()),
                (ctx, a) -> catalogs.get().publicConfiguration());
        ops.register(
                "models.add",
                "Add a model without changing existing models or the default. Reuse a stored key only with explicit credentialsFrom for the same provider and baseUrl. New keys must be entered securely in Console Models, never through chat or tool arguments.",
                object(
                        fields(
                                "revision",
                                revision(),
                                "model",
                                object(addFields, "id", "name", "model"),
                                "credentialsFrom",
                                text(
                                        "Optional existing model ID whose stored key should be reused for this same provider and endpoint.")),
                        "revision",
                        "model"),
                (ctx, a) ->
                        catalogs.get()
                                .add(
                                        a.get("revision").asLong(),
                                        json.treeToValue(a.get("model"), ModelProfile.class),
                                        a.path("credentialsFrom").asText(null)));
        ops.register(
                "models.update",
                "Update only the supplied settings of one model. Preserve its stable ID, other models and default. Changing the provider/endpoint clears the previous key. Do not switch models autonomously.",
                object(
                        fields(
                                "revision",
                                revision(),
                                "id",
                                text("Existing model ID."),
                                "changes",
                                object(modelFields)),
                        "revision",
                        "id",
                        "changes"),
                (ctx, a) -> {
                    ModelCatalog catalog = catalogs.get();
                    ModelProfile p =
                            catalog.publicConfiguration().models.stream()
                                    .filter(m -> m.id.equals(a.get("id").asText()))
                                    .findFirst()
                                    .orElseThrow(
                                            () -> new IllegalArgumentException("Model not found"));
                    json.readerForUpdating(p).readValue(a.get("changes"));
                    return catalog.update(a.get("revision").asLong(), p);
                });
        ObjectNode editId =
                object(
                        fields("revision", revision(), "id", text("Existing model ID.")),
                        "revision",
                        "id");
        ops.register(
                "models.remove",
                "Remove a model only when requested by the user. Choose another default first if needed.",
                editId,
                (ctx, a) ->
                        catalogs.get().remove(a.get("revision").asLong(), a.get("id").asText()));
        ops.register(
                "models.default",
                "Set the default model only when explicitly requested by the user. Existing runs keep their model snapshot.",
                editId,
                (ctx, a) ->
                        catalogs.get()
                                .selectDefault(a.get("revision").asLong(), a.get("id").asText()));
        ops.register(
                "models.test",
                "Test a configured model with a short text request. This does not verify image input support. May incur a model call charge.",
                object(fields("id", text("Enabled model ID.")), "id"),
                (ctx, a) -> catalogs.get().testConnection(a.get("id").asText()));
    }
}

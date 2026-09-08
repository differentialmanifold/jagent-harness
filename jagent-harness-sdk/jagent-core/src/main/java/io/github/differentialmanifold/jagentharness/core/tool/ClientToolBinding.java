package io.github.differentialmanifold.jagentharness.core.tool;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.protocol.ToolDescriptor;

public final class ClientToolBinding implements ToolDefinition {
    private final ToolDescriptor spec;
    private final JsonNode schema;

    public ClientToolBinding(ToolDescriptor spec, ObjectMapper json) {
        this.spec = spec;
        this.schema = json.valueToTree(spec.parameters);
    }

    public String getName() {
        return spec.name;
    }

    public String getDescription() {
        return spec.description;
    }

    public JsonNode getParametersSchema() {
        return schema;
    }

    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
        throw new UnsupportedOperationException("Remote tool metadata has no local implementation");
    }
}

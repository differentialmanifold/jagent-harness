package io.github.differentialmanifold.jagentharness.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.protocol.ToolDescriptor;
import java.util.Map;

/** Shared Java tool contract. A tool's registration determines where it executes. */
public interface ToolDefinition {
    String getName();

    String getDescription();

    JsonNode getParametersSchema();

    ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception;

    default ToolDescriptor descriptor() {
        ToolDescriptor descriptor = new ToolDescriptor();
        descriptor.name = getName();
        descriptor.description = getDescription();
        descriptor.parameters = new ObjectMapper().convertValue(getParametersSchema(), Map.class);
        return descriptor;
    }
}

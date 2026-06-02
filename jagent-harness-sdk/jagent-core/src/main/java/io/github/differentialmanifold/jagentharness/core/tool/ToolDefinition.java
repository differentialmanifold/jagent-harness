package io.github.differentialmanifold.jagentharness.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface ToolDefinition {

    String getName();

    String getDescription();

    JsonNode getParametersSchema();

    ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception;
}

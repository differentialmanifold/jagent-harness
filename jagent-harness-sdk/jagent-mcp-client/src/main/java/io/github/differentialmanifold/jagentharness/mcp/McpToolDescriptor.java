package io.github.differentialmanifold.jagentharness.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public class McpToolDescriptor {

    private final String name;
    private final String description;
    private final JsonNode inputSchema;

    public McpToolDescriptor(String name, String description, JsonNode inputSchema) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public JsonNode getInputSchema() {
        return inputSchema;
    }
}

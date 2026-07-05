package io.github.differentialmanifold.jagentharness.spring.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public class ToolInfoResponse {

    private String name;
    private String description;
    private JsonNode parametersSchema;
    private boolean enabled;

    public ToolInfoResponse() {
    }

    public ToolInfoResponse(String name, String description, JsonNode parametersSchema) {
        this(name, description, parametersSchema, true);
    }

    public ToolInfoResponse(String name, String description, JsonNode parametersSchema, boolean enabled) {
        this.name = name;
        this.description = description;
        this.parametersSchema = parametersSchema;
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public JsonNode getParametersSchema() {
        return parametersSchema;
    }

    public boolean isEnabled() {
        return enabled;
    }
}

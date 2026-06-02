package io.github.differentialmanifold.jagentharness.spring.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public class ToolInfoResponse {

    private String name;
    private String description;
    private JsonNode parametersSchema;

    public ToolInfoResponse() {
    }

    public ToolInfoResponse(String name, String description, JsonNode parametersSchema) {
        this.name = name;
        this.description = description;
        this.parametersSchema = parametersSchema;
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
}

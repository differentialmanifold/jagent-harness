package io.github.differentialmanifold.jagentharness.core.tool.support;

import com.fasterxml.jackson.databind.JsonNode;

public final class ToolArguments {

    private ToolArguments() {
    }

    public static String requiredText(JsonNode arguments, String name) {
        JsonNode node = arguments.path(name);
        if (node.isMissingNode() || node.isNull() || node.asText().trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return node.asText();
    }

    public static String requiredString(JsonNode arguments, String name) {
        JsonNode node = arguments.path(name);
        if (node.isMissingNode() || node.isNull()) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return node.asText();
    }
}

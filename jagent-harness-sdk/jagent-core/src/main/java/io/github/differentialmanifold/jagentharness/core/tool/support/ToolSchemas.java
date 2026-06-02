package io.github.differentialmanifold.jagentharness.core.tool.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ToolSchemas {

    private ToolSchemas() {
    }

    public static ObjectNode objectSchema(ObjectMapper objectMapper, ObjectNode properties, String... required) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        if (required != null && required.length > 0) {
            ArrayNode requiredNode = objectMapper.createArrayNode();
            for (String name : required) {
                requiredNode.add(name);
            }
            schema.set("required", requiredNode);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    public static ObjectNode stringProperty(ObjectMapper objectMapper, String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    public static ObjectNode integerProperty(ObjectMapper objectMapper, String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }

    public static ObjectNode booleanProperty(ObjectMapper objectMapper, String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "boolean");
        node.put("description", description);
        return node;
    }
}

package io.github.differentialmanifold.jagentharness.core.tool.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ToolSchemas {

    private ToolSchemas() {}

    public static ObjectNode objectSchema(
            ObjectMapper objectMapper, ObjectNode properties, String... required) {
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

    public static void validate(JsonNode value, JsonNode schema, String path) {
        String type = schema.path("type").asText();
        if (value.isNull() && schema.path("nullable").asBoolean(false)) return;
        if (value.isNull()
                || ("string".equals(type) && !value.isTextual())
                || ("integer".equals(type)
                        && (!value.isIntegralNumber() || !value.canConvertToLong()))
                || ("number".equals(type) && !value.isNumber())
                || ("boolean".equals(type) && !value.isBoolean())
                || ("array".equals(type) && !value.isArray())
                || ("object".equals(type) && !value.isObject()))
            throw new IllegalArgumentException("Invalid field type: " + path);
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) if (option.equals(value)) found = true;
            if (!found) throw new IllegalArgumentException("Invalid choice: " + path);
        }
        if (value.isArray())
            for (JsonNode item : value) validate(item, schema.path("items"), path + "[]");
        if (value.isObject()) {
            for (JsonNode key : schema.path("required"))
                if (!value.has(key.asText()))
                    throw new IllegalArgumentException(
                            "Missing required field: " + path + "." + key.asText());
            value.fields()
                    .forEachRemaining(
                            field -> {
                                JsonNode child = schema.path("properties").get(field.getKey());
                                if (child == null
                                        && schema.path("additionalProperties").asBoolean(false))
                                    return;
                                if (child == null)
                                    throw new IllegalArgumentException(
                                            "Unexpected field: " + path + "." + field.getKey());
                                validate(field.getValue(), child, path + "." + field.getKey());
                            });
        }
    }
}

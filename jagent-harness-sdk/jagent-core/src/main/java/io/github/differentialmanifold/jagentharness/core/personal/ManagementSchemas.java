package io.github.differentialmanifold.jagentharness.core.personal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;

/** Small schema helpers shared by the registered management operations. */
public final class ManagementSchemas {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ManagementSchemas() {}

    public static ObjectNode fields(Object... pairs) {
        ObjectNode result = JSON.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2)
            result.set((String) pairs[i], (JsonNode) pairs[i + 1]);
        return result;
    }

    public static ObjectNode object(ObjectNode fields, String... required) {
        return ToolSchemas.objectSchema(JSON, fields, required);
    }

    public static ObjectNode text(String description) {
        return property("string", description);
    }

    public static ObjectNode property(String type, String description) {
        return JSON.createObjectNode().put("type", type).put("description", description);
    }

    public static ObjectNode strings(String description) {
        ObjectNode node = property("array", description);
        node.set("items", text("Value"));
        return node;
    }

    public static ObjectNode revision() {
        return property(
                "integer",
                "Current revision returned by read/list or the last edit. Reload after a conflict.");
    }
}

package io.github.differentialmanifold.jagentharness.core.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSnapshotTest {
    @Test
    void freezesSchemaAndMembershipUntilTheNextRequest() {
        ObjectNode schema =
                new ObjectMapper()
                        .createObjectNode()
                        .put("type", "object")
                        .put("description", "before");
        ToolDefinition original =
                new ToolDefinition() {
                    @Override
                    public String getName() {
                        return "business";
                    }

                    @Override
                    public String getDescription() {
                        return "business function";
                    }

                    @Override
                    public JsonNode getParametersSchema() {
                        return schema;
                    }

                    @Override
                    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
                        return ToolExecutionResult.of("ok");
                    }
                };
        Map<String, ToolDefinition> snapshot =
                ToolSnapshot.capture(Collections.singletonList(original));
        schema.put("description", "after");
        assertEquals(
                "before",
                snapshot.get("business").getParametersSchema().path("description").asText());
        ((ObjectNode) snapshot.get("business").getParametersSchema()).put("description", "mutated");
        assertEquals(
                "before",
                snapshot.get("business").getParametersSchema().path("description").asText());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertEquals(
                "after",
                ToolSnapshot.capture(Collections.singletonList(original))
                        .get("business")
                        .getParametersSchema()
                        .path("description")
                        .asText());
    }
}

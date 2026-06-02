package io.github.differentialmanifold.jagentharness.example.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import org.springframework.stereotype.Component;

@Component
public class OrderQueryTool implements ToolDefinition {

    private final ObjectMapper objectMapper;

    public OrderQueryTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "order_query";
    }

    @Override
    public String getDescription() {
        return "Query order status from the host business system.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("orderId", ToolSchemas.stringProperty(objectMapper, "Business order id."));
        return ToolSchemas.objectSchema(objectMapper, properties, "orderId");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
        String orderId = ToolArguments.requiredText(arguments, "orderId");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("orderId", orderId);
        result.put("status", "paid");
        result.put("nextStep", "wait_for_shipment");
        return ToolExecutionResult.of(result.toString());
    }
}

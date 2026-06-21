package io.github.differentialmanifold.jagentharness.example.business.tool;

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
public class InventoryCheckTool implements ToolDefinition {

    private final ObjectMapper objectMapper;

    public InventoryCheckTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "inventory_check";
    }

    @Override
    public String getDescription() {
        return "Check stock and delivery estimate for a demo shopping product.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("productId", ToolSchemas.stringProperty(objectMapper, "Product id returned by product_search."));
        return ToolSchemas.objectSchema(objectMapper, properties, "productId");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
        String productId = ToolArguments.requiredText(arguments, "productId");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("productId", productId);
        result.put("inStock", inStock(productId));
        result.put("deliveryEstimate", deliveryEstimate(productId));
        return ToolExecutionResult.of(result.toString());
    }

    private boolean inStock(String productId) {
        if ("CAM-200".equals(productId)) {
            return true;
        }
        if ("CAM-100".equals(productId)) {
            return true;
        }
        return !"HUB-100".equals(productId);
    }

    private String deliveryEstimate(String productId) {
        if (!inStock(productId)) {
            return "out_of_stock";
        }
        if ("CAM-100".equals(productId) || "CAM-200".equals(productId)) {
            return "today";
        }
        return "tomorrow";
    }
}

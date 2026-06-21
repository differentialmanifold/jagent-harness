package io.github.differentialmanifold.jagentharness.example.businessconsole.tool;

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
public class CartAddTool implements ToolDefinition {

    private final ObjectMapper objectMapper;

    public CartAddTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "cart_add";
    }

    @Override
    public String getDescription() {
        return "Add one selected demo shopping product to the cart.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("productId", ToolSchemas.stringProperty(objectMapper, "Product id returned by product_search."));
        properties.set("quantity", ToolSchemas.integerProperty(objectMapper, "Quantity to add."));
        return ToolSchemas.objectSchema(objectMapper, properties, "productId", "quantity");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
        String productId = ToolArguments.requiredText(arguments, "productId");
        int quantity = Math.max(1, arguments.path("quantity").asInt(1));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("cartItemId", "CART-ITEM-1001");
        result.put("productId", productId);
        result.put("name", productName(productId));
        result.put("quantity", quantity);
        result.put("unitPrice", productPrice(productId));
        return ToolExecutionResult.of(result.toString());
    }

    private String productName(String productId) {
        if ("CAM-200".equals(productId)) {
            return "FocusCam 2K Webcam";
        }
        if ("CAM-100".equals(productId)) {
            return "ClearMeet 1080p Webcam";
        }
        if ("MIC-100".equals(productId)) {
            return "DeskVoice USB Microphone";
        }
        if ("HUB-100".equals(productId)) {
            return "WorkHub USB-C Dock";
        }
        return "Unknown product";
    }

    private int productPrice(String productId) {
        if ("CAM-200".equals(productId)) {
            return 459;
        }
        if ("CAM-100".equals(productId)) {
            return 299;
        }
        if ("MIC-100".equals(productId)) {
            return 199;
        }
        if ("HUB-100".equals(productId)) {
            return 399;
        }
        return 0;
    }
}

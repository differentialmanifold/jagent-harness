package io.github.differentialmanifold.jagentharness.example.businessconsole.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import org.springframework.stereotype.Component;

@Component
public class ProductSearchTool implements ToolDefinition {

    private final ObjectMapper objectMapper;

    public ProductSearchTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "product_search";
    }

    @Override
    public String getDescription() {
        return "Search demo shopping products by user need and optional budget.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("query", ToolSchemas.stringProperty(objectMapper, "Product need or search keywords."));
        properties.set("maxPrice", ToolSchemas.integerProperty(objectMapper, "Optional maximum price."));
        return ToolSchemas.objectSchema(objectMapper, properties, "query");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
        String query = ToolArguments.requiredText(arguments, "query");
        int maxPrice = arguments.path("maxPrice").asInt(Integer.MAX_VALUE);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("query", query);
        ArrayNode products = objectMapper.createArrayNode();
        addProduct(products, "CAM-100", "ClearMeet 1080p Webcam", 299,
                "remote-work,webcam,1080p,usb", maxPrice);
        addProduct(products, "CAM-200", "FocusCam 2K Webcam", 459,
                "remote-work,webcam,2k,noise-reduction", maxPrice);
        addProduct(products, "MIC-100", "DeskVoice USB Microphone", 199,
                "remote-work,microphone,usb", maxPrice);
        addProduct(products, "HUB-100", "WorkHub USB-C Dock", 399,
                "remote-work,dock,usb-c", maxPrice);
        result.set("products", products);
        return ToolExecutionResult.of(result.toString());
    }

    private void addProduct(ArrayNode products,
                            String productId,
                            String name,
                            int price,
                            String tags,
                            int maxPrice) {
        if (price > maxPrice) {
            return;
        }
        ObjectNode product = objectMapper.createObjectNode();
        product.put("productId", productId);
        product.put("name", name);
        product.put("price", price);
        product.put("tags", tags);
        products.add(product);
    }
}

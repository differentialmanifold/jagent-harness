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
public class CustomerLookupTool implements ToolDefinition {

    private final ObjectMapper objectMapper;

    public CustomerLookupTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "customer_lookup";
    }

    @Override
    public String getDescription() {
        return "Look up customer profile, account tier, recent order, and open support state in the host business system.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("customerId", ToolSchemas.stringProperty(objectMapper, "Customer id in the business system."));
        return ToolSchemas.objectSchema(objectMapper, properties, "customerId");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
        String customerId = ToolArguments.requiredText(arguments, "customerId");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("customerId", customerId);
        result.put("name", "Ada Chen");
        result.put("accountTier", "enterprise");
        result.put("accountStatus", "active");
        result.put("lastOrderId", "ORD-2026-0619");
        result.put("lastOrderStatus", "delivered");
        result.put("openTickets", 1);
        result.put("preferredLanguage", "zh-CN");
        return ToolExecutionResult.of(result.toString());
    }
}

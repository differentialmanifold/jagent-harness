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
public class RefundPolicyCheckTool implements ToolDefinition {

    private final ObjectMapper objectMapper;

    public RefundPolicyCheckTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "refund_policy_check";
    }

    @Override
    public String getDescription() {
        return "Check refund eligibility and required business action for a customer order.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("customerId", ToolSchemas.stringProperty(objectMapper, "Customer id in the business system."));
        properties.set("orderId", ToolSchemas.stringProperty(objectMapper, "Order id to evaluate."));
        return ToolSchemas.objectSchema(objectMapper, properties, "customerId", "orderId");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
        String customerId = ToolArguments.requiredText(arguments, "customerId");
        String orderId = ToolArguments.requiredText(arguments, "orderId");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("customerId", customerId);
        result.put("orderId", orderId);
        result.put("eligible", true);
        result.put("refundWindowDaysRemaining", 12);
        result.put("approvalRequired", false);
        result.put("recommendedAction", "offer_refund_or_account_credit");
        return ToolExecutionResult.of(result.toString());
    }
}

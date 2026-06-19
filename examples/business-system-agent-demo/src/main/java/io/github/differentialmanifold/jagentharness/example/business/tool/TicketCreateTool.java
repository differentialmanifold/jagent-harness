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
public class TicketCreateTool implements ToolDefinition {

    private final ObjectMapper objectMapper;

    public TicketCreateTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "ticket_create";
    }

    @Override
    public String getDescription() {
        return "Create a follow-up support ticket in the host business system.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("customerId", ToolSchemas.stringProperty(objectMapper, "Customer id in the business system."));
        properties.set("summary", ToolSchemas.stringProperty(objectMapper, "Short business summary for the ticket."));
        properties.set("priority", ToolSchemas.stringProperty(objectMapper, "Ticket priority such as low, normal, high, or urgent."));
        return ToolSchemas.objectSchema(objectMapper, properties, "customerId", "summary", "priority");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
        String customerId = ToolArguments.requiredText(arguments, "customerId");
        String summary = ToolArguments.requiredText(arguments, "summary");
        String priority = ToolArguments.requiredText(arguments, "priority");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("ticketId", "TCK-2026-00042");
        result.put("customerId", customerId);
        result.put("summary", summary);
        result.put("priority", priority);
        result.put("status", "open");
        result.put("ownerTeam", "customer-operations");
        return ToolExecutionResult.of(result.toString());
    }
}

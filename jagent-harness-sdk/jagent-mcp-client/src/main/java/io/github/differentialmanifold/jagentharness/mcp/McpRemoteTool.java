package io.github.differentialmanifold.jagentharness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;

public class McpRemoteTool implements ToolDefinition {

    private final String modelName;
    private final McpToolDescriptor descriptor;
    private final McpClient client;
    private final ObjectMapper objectMapper;

    public McpRemoteTool(String serverName,
                         McpToolDescriptor descriptor,
                         McpClient client,
                         ObjectMapper objectMapper) {
        this.modelName = McpToolNames.modelName(serverName, descriptor.getName());
        this.descriptor = descriptor;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return modelName;
    }

    @Override
    public String getDescription() {
        return descriptor.getDescription();
    }

    @Override
    public JsonNode getParametersSchema() {
        JsonNode schema = descriptor.getInputSchema();
        if (schema != null && schema.isObject()) {
            return schema;
        }
        ObjectNode empty = objectMapper.createObjectNode();
        empty.put("type", "object");
        empty.set("properties", objectMapper.createObjectNode());
        return empty;
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        JsonNode result = client.callTool(descriptor.getName(), arguments, context.getStopSignal());
        if (result.path("isError").asBoolean(false)) {
            return ToolExecutionResult.error(textContent(result.path("content")));
        }
        JsonNode content = result.path("content");
        if (isTextOnly(content) && !result.has("structuredContent")) {
            return ToolExecutionResult.of(textContent(content));
        }
        return ToolExecutionResult.of(objectMapper.writeValueAsString(result));
    }

    public String getRemoteName() {
        return descriptor.getName();
    }

    private boolean isTextOnly(JsonNode content) {
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode item : content) {
            if (!"text".equals(item.path("type").asText())) {
                return false;
            }
        }
        return true;
    }

    private String textContent(JsonNode content) {
        if (!content.isArray()) {
            return content.isMissingNode() ? "MCP tool returned an error" : content.toString();
        }
        StringBuilder result = new StringBuilder();
        for (JsonNode item : content) {
            String text = item.path("text").asText("");
            if (text.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(text);
        }
        return result.length() == 0 ? "MCP tool returned an error" : result.toString();
    }
}

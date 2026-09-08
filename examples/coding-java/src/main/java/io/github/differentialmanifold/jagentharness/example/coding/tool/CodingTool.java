package io.github.differentialmanifold.jagentharness.example.coding.tool;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.example.coding.approval.ToolApprovalRejectedException;
import io.github.differentialmanifold.jagentharness.example.coding.execution.CodingContext;
import java.util.*;

/** JSON convenience SPI for this coding application. All tools are native client tools. */
public interface CodingTool extends ToolDefinition {
    String getName();

    String getDescription();

    JsonNode getParametersSchema();

    ToolExecutionResult execute(CodingContext context, JsonNode arguments) throws Exception;

    default ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        ObjectMapper json = new ObjectMapper();
        try {
            ToolExecutionResult result =
                    execute(
                            context.requireLocalContext(CodingContext.class)
                                    .forToolCall(
                                            context.getCurrentToolCallId(),
                                            context.getCurrentToolName()),
                            arguments);
            Object value =
                    result.content instanceof String
                            ? json.readValue((String) result.content, Object.class)
                            : result.content;
            if (value instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) value;
                if (Boolean.TRUE.equals(map.get("timedOut")))
                    return ToolExecutionResult.unknown(value);
                if (map.containsKey("error")
                        || (map.get("exitCode") instanceof Number
                                && ((Number) map.get("exitCode")).intValue() != 0))
                    return ToolExecutionResult.failure(value);
            }
            return ToolExecutionResult.success(value);
        } catch (ToolApprovalRejectedException denied) {
            return ToolExecutionResult.failure(
                    Collections.singletonMap("error", denied.getMessage()));
        }
    }
}

package io.github.differentialmanifold.jagentharness.client;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Write before executing. A crash with no saved result is UNKNOWN, never an instruction to replay.
 */
final class ToolJournal {
    private final Path root;

    ToolJournal(Path root) {
        this.root = root;
    }

    Path path(String session, String call) {
        return root.resolve(
                RunnerFiles.digest((session + ":" + call).getBytes(StandardCharsets.UTF_8))
                        + ".json");
    }

    ToolExecutionResult read(String session, String call) throws Exception {
        Path file = path(session, call);
        if (!Files.exists(file))
            return ToolExecutionResult.unknown(
                    "No local result exists; inspect the actual effects before continuing");
        JsonNode record = RunnerFiles.JSON.readTree(file.toFile());
        JsonNode result = record.path("result");
        Object content =
                result.has("content")
                        ? RunnerFiles.JSON.convertValue(result.get("content"), Object.class)
                        : "Execution was interrupted; the outcome is unknown";
        switch (result.path("status").asText()) {
            case "SUCCEEDED":
                return ToolExecutionResult.success(content);
            case "FAILED":
                return ToolExecutionResult.failure(content);
            default:
                return ToolExecutionResult.unknown(content);
        }
    }

    ToolDefinition wrap(ToolDefinition tool) {
        return new ToolDefinition() {
            public String getName() {
                return tool.getName();
            }

            public String getDescription() {
                return tool.getDescription();
            }

            public JsonNode getParametersSchema() {
                return tool.getParametersSchema();
            }

            public synchronized ToolExecutionResult execute(ToolContext context, JsonNode arguments)
                    throws Exception {
                Path file = path(context.getSessionId(), context.getCurrentToolCallId());
                if (Files.exists(file)) {
                    JsonNode saved = RunnerFiles.JSON.readTree(file.toFile());
                    if (!getName().equals(saved.path("tool").asText())
                            || !arguments.equals(saved.get("arguments")))
                        throw new IllegalArgumentException(
                                "Tool call ID was reused with different arguments");
                    return read(context.getSessionId(), context.getCurrentToolCallId());
                }
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("tool", getName());
                record.put("arguments", arguments);
                record.put("state", "STARTED");
                RunnerFiles.save(file, record);
                ToolExecutionResult result;
                try {
                    result = tool.execute(context, arguments);
                    if (result == null)
                        result = ToolExecutionResult.unknown("Tool returned no result");
                } catch (ToolApprovalRejectedException denied) {
                    result = ToolExecutionResult.failure(denied.getMessage());
                } catch (Exception e) {
                    result = ToolExecutionResult.unknown(e.toString());
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("status", result.status);
                value.put("content", result.content);
                record.put("result", value);
                record.put("state", "FINISHED");
                RunnerFiles.save(file, record);
                return result;
            }
        };
    }
}

package io.github.differentialmanifold.jagentharness.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Freezes request membership, metadata and dispatch targets for one model request. */
public final class ToolSnapshot {
    private ToolSnapshot() {}

    public static Map<String, ToolDefinition> capture(Collection<ToolDefinition> tools) {
        Map<String, ToolDefinition> result = new TreeMap<>();
        for (ToolDefinition tool : tools) {
            String name = tool.getName();
            String description = tool.getDescription();
            JsonNode parameters = tool.getParametersSchema();
            JsonNode schema = parameters == null ? null : parameters.deepCopy();
            result.put(
                    name,
                    new ToolDefinition() {
                        @Override
                        public String getName() {
                            return name;
                        }

                        @Override
                        public String getDescription() {
                            return description;
                        }

                        @Override
                        public JsonNode getParametersSchema() {
                            return schema == null ? null : schema.deepCopy();
                        }

                        @Override
                        public ToolExecutionResult execute(ToolContext context, JsonNode arguments)
                                throws Exception {
                            return tool.execute(context, arguments);
                        }
                    });
        }
        return Collections.unmodifiableMap(result);
    }
}

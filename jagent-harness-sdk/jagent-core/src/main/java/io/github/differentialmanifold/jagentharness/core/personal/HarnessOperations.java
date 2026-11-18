package io.github.differentialmanifold.jagentharness.core.personal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import java.util.*;

/** Explicit business operations, discovered on demand. No HTTP dispatch or reflection. */
public final class HarnessOperations {
    @FunctionalInterface
    public interface Handler {
        Object execute(ToolContext context, JsonNode arguments) throws Exception;
    }

    private final Map<String, Operation> operations = new LinkedHashMap<>();

    public void register(String name, String description, JsonNode schema, Handler handler) {
        if (!name.matches("[a-z]+\\.[a-zA-Z]+") || operations.containsKey(name))
            throw new IllegalArgumentException(
                    "Invalid or duplicate management operation: " + name);
        operations.put(name, new Operation(name, description, schema.deepCopy(), handler));
    }

    public Object describe(String resource) {
        if (resource == null || resource.isEmpty()) {
            Set<String> resources = new LinkedHashSet<>();
            operations.keySet().forEach(name -> resources.add(name.split("\\.")[0]));
            return Collections.singletonMap("resources", resources);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Operation op : operations.values()) {
            if (!op.name.startsWith(resource + ".")) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("operation", op.name);
            row.put("description", op.description);
            row.put("argumentsSchema", op.schema.deepCopy());
            result.add(row);
        }
        if (result.isEmpty())
            throw new IllegalArgumentException(
                    "Unknown resource; describe without resource to list available resources");
        return result;
    }

    Object execute(ToolContext context, String name, JsonNode arguments) throws Exception {
        Operation op = operations.get(name);
        if (op == null) throw new IllegalArgumentException("Unknown operation; use describe first");
        ToolSchemas.validate(arguments, op.schema, "arguments");
        context.getStopSignal().throwIfAborted();
        return op.handler.execute(context, arguments);
    }

    private static final class Operation {
        final String name, description;
        final JsonNode schema;
        final Handler handler;

        Operation(String name, String description, JsonNode schema, Handler handler) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.handler = handler;
        }
    }
}

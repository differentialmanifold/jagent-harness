package io.github.differentialmanifold.jagentharness.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<String, ToolDefinition>();

    public ToolRegistry() {
    }

    public ToolRegistry(List<ToolDefinition> toolDefinitions) {
        if (toolDefinitions != null) {
            for (ToolDefinition toolDefinition : toolDefinitions) {
                register(toolDefinition);
            }
        }
    }

    public synchronized void register(ToolDefinition tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        tools.remove(tool.getName());
        tools.put(tool.getName(), tool);
    }

    public synchronized ToolDefinition get(String name) {
        return tools.get(name);
    }

    public synchronized Collection<ToolDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<ToolDefinition>(tools.values()));
    }
}

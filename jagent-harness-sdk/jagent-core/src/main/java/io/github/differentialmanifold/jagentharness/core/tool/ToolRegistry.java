package io.github.differentialmanifold.jagentharness.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<String, ToolDefinition>();
    private final List<ToolProvider> providers = new ArrayList<ToolProvider>();

    public ToolRegistry() {
    }

    public ToolRegistry(List<ToolDefinition> toolDefinitions) {
        this(toolDefinitions, null);
    }

    public ToolRegistry(List<ToolDefinition> toolDefinitions, List<ToolProvider> toolProviders) {
        if (toolDefinitions != null) {
            for (ToolDefinition toolDefinition : toolDefinitions) {
                register(toolDefinition);
            }
        }
        if (toolProviders != null) {
            for (ToolProvider toolProvider : toolProviders) {
                registerProvider(toolProvider);
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

    public synchronized void registerProvider(ToolProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("toolProvider must not be null");
        }
        if (!providers.contains(provider)) {
            providers.add(provider);
        }
    }

    public Collection<ToolDefinition> all() {
        return all(null);
    }

    public Collection<ToolDefinition> all(io.github.differentialmanifold.jagentharness.core.agent.AgentContext context) {
        Map<String, ToolDefinition> resolved;
        List<ToolProvider> providerSnapshot;
        synchronized (this) {
            resolved = new LinkedHashMap<String, ToolDefinition>(tools);
            providerSnapshot = new ArrayList<ToolProvider>(providers);
        }
        for (ToolProvider provider : providerSnapshot) {
            Collection<ToolDefinition> provided = provider.listTools(context);
            if (provided == null) {
                continue;
            }
            for (ToolDefinition tool : provided) {
                if (tool == null) {
                    continue;
                }
                if (resolved.containsKey(tool.getName())) {
                    throw new IllegalStateException("Duplicate tool name: " + tool.getName());
                }
                resolved.put(tool.getName(), tool);
            }
        }
        return Collections.unmodifiableList(new ArrayList<ToolDefinition>(resolved.values()));
    }
}

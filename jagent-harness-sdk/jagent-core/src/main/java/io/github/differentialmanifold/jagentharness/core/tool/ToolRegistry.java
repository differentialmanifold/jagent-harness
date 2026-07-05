package io.github.differentialmanifold.jagentharness.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;

public class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<String, ToolDefinition>();
    private final List<ToolProvider> providers = new ArrayList<ToolProvider>();
    private final List<ToolAvailabilityPolicy> availabilityPolicies = new ArrayList<ToolAvailabilityPolicy>();

    public ToolRegistry() {
    }

    public ToolRegistry(List<ToolDefinition> toolDefinitions) {
        this(toolDefinitions, null);
    }

    public ToolRegistry(List<ToolDefinition> toolDefinitions, List<ToolProvider> toolProviders) {
        this(toolDefinitions, toolProviders, null);
    }

    public ToolRegistry(List<ToolDefinition> toolDefinitions,
                        List<ToolProvider> toolProviders,
                        List<ToolAvailabilityPolicy> toolAvailabilityPolicies) {
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
        if (toolAvailabilityPolicies != null) {
            for (ToolAvailabilityPolicy policy : toolAvailabilityPolicies) {
                registerAvailabilityPolicy(policy);
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

    public ToolDefinition get(String name, AgentContext context) {
        if (name == null) {
            return null;
        }
        for (ToolDefinition tool : all(context)) {
            if (name.equals(tool.getName())) {
                return tool;
            }
        }
        return null;
    }

    public synchronized void registerProvider(ToolProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("toolProvider must not be null");
        }
        if (!providers.contains(provider)) {
            providers.add(provider);
        }
    }

    public synchronized void registerAvailabilityPolicy(ToolAvailabilityPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("toolAvailabilityPolicy must not be null");
        }
        if (!availabilityPolicies.contains(policy)) {
            availabilityPolicies.add(policy);
        }
    }

    public synchronized Collection<ToolDefinition> registeredTools() {
        return Collections.unmodifiableList(new ArrayList<ToolDefinition>(tools.values()));
    }

    public Collection<ToolDefinition> all() {
        return all(null);
    }

    public Collection<ToolDefinition> all(AgentContext context) {
        Map<String, ToolDefinition> resolved;
        List<ToolProvider> providerSnapshot;
        List<ToolAvailabilityPolicy> policySnapshot;
        synchronized (this) {
            resolved = new LinkedHashMap<String, ToolDefinition>(tools);
            providerSnapshot = new ArrayList<ToolProvider>(providers);
            policySnapshot = new ArrayList<ToolAvailabilityPolicy>(availabilityPolicies);
        }
        if (!policySnapshot.isEmpty()) {
            Collection<ToolDefinition> available = new ArrayList<ToolDefinition>(resolved.values());
            for (ToolAvailabilityPolicy policy : policySnapshot) {
                Collection<ToolDefinition> filtered = policy.filter(available, context);
                available = filtered == null
                        ? Collections.<ToolDefinition>emptyList()
                        : filtered;
            }
            resolved.clear();
            for (ToolDefinition tool : available) {
                if (tool != null) {
                    resolved.put(tool.getName(), tool);
                }
            }
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

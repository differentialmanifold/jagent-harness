package io.github.differentialmanifold.jagentharness.core.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ToolSelectionSnapshot {

    private final boolean configured;
    private final Set<String> enabledTools;

    public ToolSelectionSnapshot(boolean configured, Collection<String> enabledTools) {
        this.configured = configured;
        Set<String> copy = new LinkedHashSet<String>();
        if (enabledTools != null) {
            copy.addAll(enabledTools);
        }
        this.enabledTools = Collections.unmodifiableSet(copy);
    }

    public static ToolSelectionSnapshot defaults() {
        return new ToolSelectionSnapshot(false, Collections.<String>emptySet());
    }

    public boolean isConfigured() {
        return configured;
    }

    public Set<String> getEnabledTools() {
        return enabledTools;
    }

    public boolean isEnabled(String toolName) {
        return !configured || enabledTools.contains(toolName);
    }
}

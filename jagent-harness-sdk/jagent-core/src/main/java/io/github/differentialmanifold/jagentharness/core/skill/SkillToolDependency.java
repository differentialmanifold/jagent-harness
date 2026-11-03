package io.github.differentialmanifold.jagentharness.core.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exact remote MCP names requested by a skill, not model-facing function names. */
public final class SkillToolDependency {
    private final String server;
    private final List<String> tools;

    public SkillToolDependency(String server, List<String> tools) {
        this.server = server;
        this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
    }

    public String getServer() {
        return server;
    }

    public List<String> getTools() {
        return tools;
    }
}

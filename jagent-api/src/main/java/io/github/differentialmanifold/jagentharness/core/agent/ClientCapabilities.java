package io.github.differentialmanifold.jagentharness.core.agent;

import io.github.differentialmanifold.jagentharness.protocol.ToolDescriptor;
import java.util.*;

/** The remote caller's capabilities, supplied for one conversation request. */
public final class ClientCapabilities {
    public static final ClientCapabilities NONE =
            new ClientCapabilities(Collections.emptyList(), "");
    private final List<ToolDescriptor> tools;
    private final String instructions;

    public ClientCapabilities(List<ToolDescriptor> tools, String instructions) {
        this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
        this.instructions = instructions == null ? "" : instructions;
    }

    public List<ToolDescriptor> getTools() {
        return tools;
    }

    public String getInstructions() {
        return instructions;
    }
}

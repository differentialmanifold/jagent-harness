package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.mcp.McpToolDescriptor;

public class McpServerRuntimeStatus {

    private final String status;
    private final String error;
    private final String protocolVersion;
    private final List<String> tools;
    private final List<String> availableTools;
    private final List<McpToolDescriptor> toolDetails;

    McpServerRuntimeStatus(String status, String error, String protocolVersion, List<String> tools) {
        this(status, error, protocolVersion, tools, tools, Collections.<McpToolDescriptor>emptyList());
    }

    McpServerRuntimeStatus(String status,
                           String error,
                           String protocolVersion,
                           List<String> tools,
                           List<String> availableTools) {
        this(status, error, protocolVersion, tools, availableTools, Collections.<McpToolDescriptor>emptyList());
    }

    McpServerRuntimeStatus(String status,
                           String error,
                           String protocolVersion,
                           List<String> tools,
                           List<String> availableTools,
                           List<McpToolDescriptor> toolDetails) {
        this.status = status;
        this.error = error;
        this.protocolVersion = protocolVersion;
        this.tools = tools == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(tools));
        this.availableTools = availableTools == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(availableTools));
        this.toolDetails = toolDetails == null
                ? Collections.<McpToolDescriptor>emptyList()
                : Collections.unmodifiableList(new ArrayList<McpToolDescriptor>(toolDetails));
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public List<String> getTools() {
        return tools;
    }

    public List<String> getAvailableTools() {
        return availableTools;
    }

    public List<McpToolDescriptor> getToolDetails() {
        return toolDetails;
    }
}

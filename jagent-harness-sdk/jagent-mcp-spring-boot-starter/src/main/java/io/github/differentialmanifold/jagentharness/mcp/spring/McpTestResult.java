package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.mcp.McpToolDescriptor;

public class McpTestResult {

    private final boolean success;
    private final String error;
    private final String protocolVersion;
    private final List<String> tools;
    private final List<McpToolDescriptor> toolDetails;

    McpTestResult(boolean success,
                  String error,
                  String protocolVersion,
                  List<String> tools,
                  List<McpToolDescriptor> toolDetails) {
        this.success = success;
        this.error = error;
        this.protocolVersion = protocolVersion;
        this.tools = tools == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(tools));
        this.toolDetails = toolDetails == null
                ? Collections.<McpToolDescriptor>emptyList()
                : Collections.unmodifiableList(new ArrayList<McpToolDescriptor>(toolDetails));
    }

    public boolean isSuccess() {
        return success;
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

    public List<McpToolDescriptor> getToolDetails() {
        return toolDetails;
    }
}

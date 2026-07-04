package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class McpServerRuntimeStatus {

    private final String status;
    private final String error;
    private final String protocolVersion;
    private final List<String> tools;
    private final List<String> availableTools;

    McpServerRuntimeStatus(String status, String error, String protocolVersion, List<String> tools) {
        this(status, error, protocolVersion, tools, tools);
    }

    McpServerRuntimeStatus(String status,
                           String error,
                           String protocolVersion,
                           List<String> tools,
                           List<String> availableTools) {
        this.status = status;
        this.error = error;
        this.protocolVersion = protocolVersion;
        this.tools = tools == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(tools));
        this.availableTools = availableTools == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(availableTools));
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
}

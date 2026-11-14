package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** Framework-independent JSON wire contract. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class RunnerInfo {
    public String runnerId;
    public String name;
    public String runtime = "java";
    public String description = "", platform = "";
    public List<String> capabilities = new ArrayList<>();
    /** Whether this terminal supports browsing and selecting a workspace per project. */
    public boolean workspaces;
    /** Absolute root on the Runner for automatic user/Agent project directories. */
    public String workspaceRoot;

    public String instructions = "";
    public List<ToolDescriptor> tools = new ArrayList<>();
    /** Optional startup diagnostics by tool name, for display only; never model input. */
    public Map<String, String> toolDiagnostics = new LinkedHashMap<>();

    public long lastSeen;
    public boolean online;
}

package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** Framework-independent JSON wire contract. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class RunnerInfo extends RunnerSummary {
    public String buildRevision, protocolVersion;
    /** Optional source locations on this terminal, reported by Coding Runners. */
    public String runnerSourceWorkspace, harnessSourceWorkspace, harnessSourceSha256;

    public String instructions = "";
    public List<ToolDescriptor> tools = new ArrayList<>();
    public Map<String, String> toolDiagnostics = new LinkedHashMap<>();
}

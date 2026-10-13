package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** Framework-independent JSON wire contract. */
public class RunnerInfo {
    public String runnerId;
    public String name;
    public String runtime = "java";
    /** Optional label for file-based tools; other scenarios leave this null. */
    public String workspace;

    public String instructions = "";
    public List<ToolDescriptor> tools = new ArrayList<>();
    public long lastSeen;
    public boolean online;
}

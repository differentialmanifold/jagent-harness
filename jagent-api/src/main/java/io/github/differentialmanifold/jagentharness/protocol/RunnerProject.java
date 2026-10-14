package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** Execution settings shared by every chat in a project. */
public class RunnerProject {
    public String projectId;
    public String runnerId;
    public String workspace;
    public List<String> enabledTools = new ArrayList<>();
}

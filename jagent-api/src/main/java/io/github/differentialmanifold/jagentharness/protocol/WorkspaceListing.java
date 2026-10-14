package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** Paths belong to the runner's filesystem, including its native path syntax. */
public class WorkspaceListing {
    public String requestId;
    public String path;
    public String parent;
    public List<String> roots = new ArrayList<>();
    public List<String> directories = new ArrayList<>();
    public boolean truncated;
    public String error;
}

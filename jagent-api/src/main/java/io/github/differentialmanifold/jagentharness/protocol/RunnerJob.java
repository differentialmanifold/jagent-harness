package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** Framework-independent JSON wire contract. */
public class RunnerJob {
    public String runnerId;
    /** Optional workspace copied from the registration. */
    public String workspace;

    public ChatRequest request;
    public String status;
    public boolean cancelled;
    public ChatResponse response;
}

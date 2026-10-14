package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** Framework-independent JSON wire contract. */
public class RunnerJob {
    public String runnerId;
    /** Optional project workspace, resolved on the executing terminal. */
    public String workspace;

    public ChatRequest request;
    public String status;
    public boolean cancelled;
    public ChatResponse response;
}

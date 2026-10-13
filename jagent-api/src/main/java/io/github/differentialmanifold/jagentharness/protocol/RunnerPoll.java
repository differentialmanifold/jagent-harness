package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** Framework-independent JSON wire contract. */
public class RunnerPoll {
    public RunnerJob job;
    public List<String> cancelledRuns = new ArrayList<>();
}

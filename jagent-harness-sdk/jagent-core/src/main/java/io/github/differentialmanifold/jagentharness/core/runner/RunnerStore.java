package io.github.differentialmanifold.jagentharness.core.runner;

import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.List;

/** Durable runner registrations, session bindings and ordinary conversation dispatch. */
public interface RunnerStore {
    List<RunnerInfo> runners();

    RunnerInfo runner(String id);

    String credentialHash(String id);

    void register(RunnerInfo info, String hash);

    void heartbeat(String id, long now);

    void bind(String sessionId, String runnerId);

    String runnerForSession(String sessionId);

    RunnerProject project(String projectId);

    void saveProject(RunnerProject project);

    void submit(RunnerJob job);

    RunnerJob job(String runId);

    List<RunnerJob> unfinished(String runnerId);

    boolean claim(String runId);

    void reopen(String runId);

    void cancel(String runId);

    void complete(String runId, ChatResponse response);
}

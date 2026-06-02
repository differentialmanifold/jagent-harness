package io.github.differentialmanifold.jagentharness.core.agent;

public interface AgentHarness {

    AgentRunResult run(String sessionId, String userText);

    AgentRunResult run(String sessionId, String userText, AgentRunOptions options);
}

package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** JSON wire contract. Independent of any framework and JSON library. */
public class ChatResponse {
    public String sessionId;
    public String runId;
    public String status;
    public String answer;
    public List<ToolInvocation> toolCalls = new ArrayList<>();
}

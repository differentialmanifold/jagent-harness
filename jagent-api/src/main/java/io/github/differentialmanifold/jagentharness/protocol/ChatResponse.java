package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** JSON wire contract. Independent of any framework and JSON library. */
public class ChatResponse {
    public String sessionId;
    public String runId;
    public String status;
    public String answer;

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public List<String> attachmentIds = new ArrayList<>();

    public List<ToolInvocation> toolCalls = new ArrayList<>();
}

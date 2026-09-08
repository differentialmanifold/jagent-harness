package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** JSON wire contract. Independent of any framework and JSON library. */
public class ChatRequest {
    public String sessionId;
    public String runId;
    public List<ChatMessage> messages = new ArrayList<>();
    public List<ToolDescriptor> clientTools = new ArrayList<>();
    public String clientInstructions = "";
    public boolean stream;
}

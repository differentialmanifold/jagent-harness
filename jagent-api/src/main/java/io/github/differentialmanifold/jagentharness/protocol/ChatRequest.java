package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** Internal conversation step carried by a claimed Runner job and its tool-result requests. */
public class ChatRequest {
    public String modelId;
    public String sessionId;
    public String runId;
    public List<ChatMessage> messages = new ArrayList<>();
    public List<ToolDescriptor> clientTools = new ArrayList<>();
    public String clientInstructions = "";
    public boolean stream;
}

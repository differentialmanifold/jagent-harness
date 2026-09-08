package io.github.differentialmanifold.jagentharness.client;

import io.github.differentialmanifold.jagentharness.protocol.*;
import java.io.IOException;
import java.util.function.Consumer;

public interface AgentTransport {
    ChatResponse chat(ChatRequest request, Consumer<StreamEvent> events) throws IOException;
}

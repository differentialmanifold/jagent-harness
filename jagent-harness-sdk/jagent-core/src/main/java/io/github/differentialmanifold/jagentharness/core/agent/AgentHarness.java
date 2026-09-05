package io.github.differentialmanifold.jagentharness.core.agent;

import java.util.List;

import io.github.differentialmanifold.jagentharness.core.message.MessageImage;

public interface AgentHarness {

    AgentRunResult run(String sessionId, String userText);

    AgentRunResult run(String sessionId, String userText, AgentRunOptions options);

    default AgentRunResult run(String sessionId,
                               String userText,
                               List<MessageImage> images) {
        return run(sessionId, userText, images, AgentRunOptions.empty());
    }

    default AgentRunResult run(String sessionId,
                               String userText,
                               List<MessageImage> images,
                               AgentRunOptions options) {
        if (images == null || images.isEmpty()) {
            return run(sessionId, userText, options);
        }
        throw new UnsupportedOperationException("This AgentHarness does not support image input");
    }
}

package io.github.differentialmanifold.jagentharness.core.agent;

import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import java.util.List;

public interface AgentHarness {

    AgentRunResult run(
            String sessionId,
            List<io.github.differentialmanifold.jagentharness.core.message.AgentMessage> messages,
            AgentRunOptions options);

    AgentRunResult run(String sessionId, String userText);

    AgentRunResult run(String sessionId, String userText, AgentRunOptions options);

    default AgentRunResult run(String sessionId, String userText, List<MessageImage> images) {
        return run(sessionId, userText, images, AgentRunOptions.empty());
    }

    default AgentRunResult run(
            String sessionId, String userText, List<MessageImage> images, AgentRunOptions options) {
        if (images == null || images.isEmpty()) {
            return run(sessionId, userText, options);
        }
        throw new UnsupportedOperationException("This AgentHarness does not support image input");
    }
}

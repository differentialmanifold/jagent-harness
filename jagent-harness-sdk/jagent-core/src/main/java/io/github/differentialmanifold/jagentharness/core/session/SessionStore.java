package io.github.differentialmanifold.jagentharness.core.session;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import java.util.List;

public interface SessionStore {

    SessionRecord requireSession(String sessionId);

    List<AgentMessage> findMessages(String sessionId);

    void appendMessage(AgentMessage message);

    default void appendMessages(List<AgentMessage> messages) {
        for (AgentMessage message : messages) appendMessage(message);
    }

    void touch(String sessionId);
}

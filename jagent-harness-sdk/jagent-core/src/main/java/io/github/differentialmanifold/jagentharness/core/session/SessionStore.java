package io.github.differentialmanifold.jagentharness.core.session;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.FailedTurn;
import java.util.List;

public interface SessionStore {

    SessionRecord requireSession(String sessionId);

    List<AgentMessage> findMessages(String sessionId);

    default List<AgentMessage> findContextMessages(String sessionId) {
        return FailedTurn.context(findMessages(sessionId));
    }

    void appendMessage(AgentMessage message);

    default void appendMessages(List<AgentMessage> messages) {
        for (AgentMessage message : messages) appendMessage(message);
    }

    void touch(String sessionId);
}

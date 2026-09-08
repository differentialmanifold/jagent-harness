package io.github.differentialmanifold.jagentharness.core.message;

import java.util.List;

public interface MessageRepository {

    void append(AgentMessage message);

    default void appendAll(List<AgentMessage> messages) {
        for (AgentMessage message : messages) append(message);
    }

    List<AgentMessage> findBySessionId(String sessionId);
}

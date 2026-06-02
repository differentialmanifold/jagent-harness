package io.github.differentialmanifold.jagentharness.core.message;

import java.util.List;

public interface MessageRepository {

    void append(AgentMessage message);

    List<AgentMessage> findBySessionId(String sessionId);
}

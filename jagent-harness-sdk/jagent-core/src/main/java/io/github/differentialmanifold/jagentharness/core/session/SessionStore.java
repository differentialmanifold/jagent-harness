package io.github.differentialmanifold.jagentharness.core.session;

import java.util.List;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;

public interface SessionStore {

    SessionRecord requireSession(String sessionId);

    List<AgentMessage> findMessages(String sessionId);

    void appendMessage(AgentMessage message);

    void touch(String sessionId);
}

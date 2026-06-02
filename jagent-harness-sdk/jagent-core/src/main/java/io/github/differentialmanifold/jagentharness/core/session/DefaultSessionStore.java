package io.github.differentialmanifold.jagentharness.core.session;

import java.util.List;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;

public class DefaultSessionStore implements SessionStore {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public DefaultSessionStore(SessionRepository sessionRepository, MessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    public SessionRecord requireSession(String sessionId) {
        SessionRecord session = sessionRepository.findBySessionId(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return session;
    }

    @Override
    public List<AgentMessage> findMessages(String sessionId) {
        return messageRepository.findBySessionId(sessionId);
    }

    @Override
    public void appendMessage(AgentMessage message) {
        messageRepository.append(message);
    }

    @Override
    public void touch(String sessionId) {
        sessionRepository.touch(sessionId);
    }
}

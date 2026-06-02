package io.github.differentialmanifold.jagentharness.core.conversation;

public interface CompactionStore {

    CompactionState findBySessionId(String sessionId);

    void save(String sessionId, String summary, String cursorMessageId);
}

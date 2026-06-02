package io.github.differentialmanifold.jagentharness.core.conversation;

public class NoopCompactionStore implements CompactionStore {

    @Override
    public CompactionState findBySessionId(String sessionId) {
        return null;
    }

    @Override
    public void save(String sessionId, String summary, String cursorMessageId) {
    }
}

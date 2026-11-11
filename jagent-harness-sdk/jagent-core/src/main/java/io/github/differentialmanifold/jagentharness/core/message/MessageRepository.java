package io.github.differentialmanifold.jagentharness.core.message;

import java.util.List;

public interface MessageRepository {

    default List<AgentMessage> findContextBySessionId(String sessionId) {
        return FailedTurn.context(findBySessionId(sessionId));
    }

    void append(AgentMessage message);

    default void appendAll(List<AgentMessage> messages) {
        for (AgentMessage message : messages) append(message);
    }

    List<AgentMessage> findBySessionId(String sessionId);

    default List<AgentMessage> findExecutionBySessionId(String sessionId) {
        return ConversationHistory.executionTail(findBySessionId(sessionId));
    }

    /** Recent messages in chronological order, independent of the model compaction cursor. */
    default List<AgentMessage> findRecentBySessionId(String sessionId, int limit) {
        return ConversationHistory.recent(findBySessionId(sessionId), limit);
    }

    /**
     * Literal, case-insensitive phrase search, scoped by the caller. Stores may index this query.
     */
    default List<AgentMessage> search(
            java.util.Collection<String> sessionIds, String query, int limit) {
        return sessionIds.stream()
                .flatMap(id -> findBySessionId(id).stream())
                .filter(
                        m ->
                                m.getContent() != null
                                        && m.getContent()
                                                .toLowerCase(java.util.Locale.ROOT)
                                                .contains(query.toLowerCase(java.util.Locale.ROOT)))
                .sorted(java.util.Comparator.comparing(AgentMessage::getCreatedAt).reversed())
                .limit(limit)
                .collect(java.util.stream.Collectors.toList());
    }
}

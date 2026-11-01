package io.github.differentialmanifold.jagentharness.core.message;

import java.util.List;

public interface MessageRepository {

    void append(AgentMessage message);

    default void appendAll(List<AgentMessage> messages) {
        for (AgentMessage message : messages) append(message);
    }

    List<AgentMessage> findBySessionId(String sessionId);

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

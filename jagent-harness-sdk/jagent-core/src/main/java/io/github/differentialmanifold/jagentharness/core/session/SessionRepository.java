package io.github.differentialmanifold.jagentharness.core.session;

import java.util.List;

public interface SessionRepository {
    default SessionRecord create(
            String title,
            String workspacePath,
            String projectName,
            String projectId,
            String ownerAgentId) {
        if (ownerAgentId != null)
            throw new UnsupportedOperationException(
                    "Project ownership is not supported by this store");
        return create(title, workspacePath, projectName, projectId);
    }

    /** Assign legacy projects only when their original creator is known. */
    default boolean assignProjectOwnerIfUnowned(String projectId, String agentId) {
        return false;
    }

    default SessionRecord createStandalone(String title) {
        throw new UnsupportedOperationException(
                "Standalone conversations are not supported by this store");
    }

    SessionRecord create(String title, String workspacePath);

    default SessionRecord create(String title, String workspacePath, String projectName) {
        return create(title, workspacePath);
    }

    default SessionRecord create(
            String title, String workspacePath, String projectName, String projectId) {
        SessionRecord session = create(title, workspacePath, projectName);
        session.setProjectId(projectId);
        return session;
    }

    SessionRecord findBySessionId(String sessionId);

    List<SessionRecord> findAll();

    void touch(String sessionId);

    void updateTitle(String sessionId, String title);

    void delete(String sessionId);
}

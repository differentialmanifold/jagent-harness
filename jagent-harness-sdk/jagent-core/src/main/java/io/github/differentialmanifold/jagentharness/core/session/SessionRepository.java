package io.github.differentialmanifold.jagentharness.core.session;

import java.util.List;

public interface SessionRepository {

    SessionRecord create(String title, String workspacePath);

    default SessionRecord create(String title, String workspacePath, String projectName) {
        return create(title, workspacePath);
    }

    default SessionRecord create(String title, String workspacePath, String projectName, String projectId) {
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

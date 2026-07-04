package io.github.differentialmanifold.jagentharness.core.session;

import java.util.List;

public interface SessionManager {

    SessionRecord createSession(String title, String workspacePath);

    default SessionRecord createSession(String title, String workspacePath, String projectName) {
        return createSession(title, workspacePath);
    }

    default SessionRecord createSession(String title, String workspacePath, String projectName, String projectId) {
        return createSession(title, workspacePath, projectName);
    }

    SessionRecord requireSession(String sessionId);

    List<SessionRecord> listSessions();

    SessionDetails getDetails(String sessionId);

    SessionRecord renameSession(String sessionId, String title);

    void deleteSession(String sessionId);
}

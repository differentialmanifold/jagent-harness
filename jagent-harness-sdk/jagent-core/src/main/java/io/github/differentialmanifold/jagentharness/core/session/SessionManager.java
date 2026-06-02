package io.github.differentialmanifold.jagentharness.core.session;

import java.util.List;

public interface SessionManager {

    SessionRecord createSession(String title, String workspacePath);

    SessionRecord requireSession(String sessionId);

    List<SessionRecord> listSessions();

    SessionDetails getDetails(String sessionId);

    SessionRecord renameSession(String sessionId, String title);

    void deleteSession(String sessionId);
}

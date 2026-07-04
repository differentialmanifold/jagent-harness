package io.github.differentialmanifold.jagentharness.core.session;

import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRepository;

public class DefaultSessionManager implements SessionManager {

    private final SessionRepository sessionRepository;
    private final TimelineEventRepository timelineEventStore;

    public DefaultSessionManager(SessionRepository sessionRepository, TimelineEventRepository timelineEventStore) {
        this.sessionRepository = sessionRepository;
        this.timelineEventStore = timelineEventStore;
    }

    public SessionRecord createSession(String title) {
        return createSession(title, null);
    }

    @Override
    public SessionRecord createSession(String title, String workspacePath) {
        return createSession(title, workspacePath, null);
    }

    @Override
    public SessionRecord createSession(String title, String workspacePath, String projectName) {
        return createSession(title, workspacePath, projectName, null);
    }

    @Override
    public SessionRecord createSession(String title, String workspacePath, String projectName, String projectId) {
        String normalizedProjectId = projectId == null ? "" : projectId.trim();
        return sessionRepository.create(
                title,
                normalizeWorkspacePath(workspacePath),
                normalizeProjectName(projectName),
                normalizedProjectId.isEmpty() ? Ids.newId("project") : normalizedProjectId);
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
    public List<SessionRecord> listSessions() {
        return sessionRepository.findAll();
    }

    @Override
    public SessionDetails getDetails(String sessionId) {
        List<AgentEvent> events = timelineEventStore == null
                ? Collections.emptyList()
                : timelineEventStore.findBySessionId(sessionId);
        return new SessionDetails(requireSession(sessionId), events);
    }

    @Override
    public SessionRecord renameSession(String sessionId, String title) {
        requireSession(sessionId);
        String normalizedTitle = title == null ? "" : title.trim();
        if (normalizedTitle.isEmpty()) {
            throw new IllegalArgumentException("title is required");
        }
        sessionRepository.updateTitle(sessionId, normalizedTitle);
        return requireSession(sessionId);
    }

    @Override
    public void deleteSession(String sessionId) {
        requireSession(sessionId);
        sessionRepository.delete(sessionId);
    }

    private String normalizeWorkspacePath(String workspacePath) {
        if (workspacePath == null) {
            return null;
        }
        String trimmed = workspacePath.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeProjectName(String projectName) {
        if (projectName == null) {
            return null;
        }
        String trimmed = projectName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

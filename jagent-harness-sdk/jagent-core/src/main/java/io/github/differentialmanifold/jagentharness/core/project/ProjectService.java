package io.github.differentialmanifold.jagentharness.core.project;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.protocol.RunnerInfo;
import io.github.differentialmanifold.jagentharness.protocol.RunnerProject;
import java.util.*;
import java.util.stream.Collectors;

/** Shared project/session operations for people and Personal Agents. */
public class ProjectService {
    private final SessionManager sessions;
    private final SessionStore history;
    private final RunnerService runners;

    public ProjectService(SessionManager sessions, SessionStore history, RunnerService runners) {
        this.sessions = sessions;
        this.history = history;
        this.runners = runners;
    }

    public List<RunnerInfo> runners() {
        return runners == null ? Collections.emptyList() : runners.list();
    }

    public String canonicalWorkspace(String runnerId, String path) {
        if (path == null || path.trim().isEmpty()) return null;
        if (runners == null) throw new IllegalArgumentException("Runner execution is disabled");
        try {
            io.github.differentialmanifold.jagentharness.protocol.WorkspaceListing result =
                    runners.browse(runnerId, path).get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (result.error != null && !result.error.isEmpty())
                throw new IllegalArgumentException(result.error);
            if (result.path == null || result.path.isEmpty())
                throw new IllegalArgumentException("Runner did not resolve the workspace");
            return result.path;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workspace lookup interrupted", e);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            throw new IllegalArgumentException("Could not resolve workspace on Runner: " + path, e);
        }
    }

    public String runnerId(String sessionId) {
        return runners == null ? null : runners.binding(sessionId);
    }

    public SessionRecord session(String sessionId) {
        return decorate(sessions.requireSession(sessionId));
    }

    public List<SessionRecord> sessions(String ownerAgentId) {
        return sessions.listSessions().stream()
                .filter(s -> !s.isStandalone() && Objects.equals(ownerAgentId, s.getOwnerAgentId()))
                .map(this::decorate)
                .collect(Collectors.toList());
    }

    public SessionRecord createSession(String ownerAgentId, CreateSessionRequest request) {
        if (request == null) request = new CreateSessionRequest();
        RunnerProject existing = runners == null ? null : runners.project(request.getProjectId());
        if (existing != null || request.getRunnerId() != null && !request.getRunnerId().isEmpty()) {
            if (runners == null) throw new IllegalArgumentException("Runner execution is disabled");
            return decorate(runners.createSession(request, existing, ownerAgentId));
        }
        String workspace = request.getWorkspacePath();
        workspace = workspace == null || workspace.trim().isEmpty() ? null : workspace.trim();
        return decorate(
                sessions.createSession(
                        request.getTitle(),
                        workspace,
                        request.getProjectName(),
                        request.getProjectId(),
                        ownerAgentId));
    }

    public SessionDetails details(String sessionId) {
        SessionDetails details = sessions.getDetails(sessionId);
        decorate(details.getSession());
        return details;
    }

    public SessionRecord renameSession(String sessionId, String title) {
        return decorate(sessions.renameSession(sessionId, title));
    }

    public void deleteSession(String sessionId) {
        sessions.deleteSession(sessionId);
    }

    public Map<String, Object> inspect(String sessionId, String before) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", session(sessionId));
        List<AgentMessage> messages = history.findMessages(sessionId);
        int end = messages.size();
        if (before != null && !before.isEmpty()) {
            end = -1;
            for (int i = 0; i < messages.size(); i++)
                if (before.equals(messages.get(i).getMessageId())) {
                    end = i;
                    break;
                }
            if (end < 0) throw new IllegalArgumentException("History cursor not found");
        }
        int start = Math.max(0, end - 40);
        out.put("messages", messages.subList(start, end));
        out.put("beforeMessageId", start == 0 ? null : messages.get(start).getMessageId());
        return out;
    }

    private SessionRecord decorate(SessionRecord session) {
        session.setRunnerId(runnerId(session.getSessionId()));
        return session;
    }
}

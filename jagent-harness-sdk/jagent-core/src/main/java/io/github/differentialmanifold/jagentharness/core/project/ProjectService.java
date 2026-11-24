package io.github.differentialmanifold.jagentharness.core.project;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.ImageInputValidator;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.support.Utf8Preview;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.protocol.RunnerInfo;
import io.github.differentialmanifold.jagentharness.protocol.RunnerProject;
import java.util.*;
import java.util.stream.Collectors;

/** Shared project/session operations for people and Personal Agents. */
public class ProjectService {
    private static final int INSPECT_PAGE_SIZE = 10;
    private static final int INSPECT_PAGE_BYTES = 8 * 1024;
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
        SessionRecord record = session(sessionId);
        ObjectNode settings = JsonNodeFactory.instance.objectNode();
        settings.put("sessionId", sessionId);
        settings.put("projectId", record.getProjectId());
        settings.put("projectName", Utf8Preview.limit(record.getProjectName(), 192));
        settings.put("title", Utf8Preview.limit(record.getTitle(), 192));
        settings.put("workspacePath", Utf8Preview.limit(record.getWorkspacePath(), 512));
        settings.put("runnerId", record.getRunnerId());
        settings.put("status", record.getStatus());
        if (record.getUpdatedAt() != null)
            settings.put("updatedAt", record.getUpdatedAt().toString());
        out.put("session", settings);

        // Read one extra row to distinguish the end of history from an exactly full page.
        List<AgentMessage> page = history.findBefore(sessionId, before, INSPECT_PAGE_SIZE + 1);
        LinkedList<ObjectNode> rows = new LinkedList<>();
        ObjectNode attachments = JsonNodeFactory.instance.objectNode();
        int bytes = 0, i = page.size();
        while (i > 0 && rows.size() < INSPECT_PAGE_SIZE) {
            AgentMessage message = page.get(i - 1);
            ObjectNode row = JsonNodeFactory.instance.objectNode();
            row.put("messageId", message.getMessageId());
            row.put("role", message.getRole());
            row.put("content", Utf8Preview.limit(message.getContent(), 1024));
            if (message.getRunId() != null)
                row.put("runId", Utf8Preview.limit(message.getRunId(), 128));
            if (message.getCreatedAt() != null)
                row.put("createdAt", message.getCreatedAt().toString());
            if (message.getToolName() != null)
                row.put("toolName", Utf8Preview.limit(message.getToolName(), 128));
            if (message.getToolCallId() != null)
                row.put("toolCallId", Utf8Preview.limit(message.getToolCallId(), 128));
            if (message.getStopReason() != null)
                row.put("stopReason", Utf8Preview.limit(message.getStopReason(), 64));
            if (!message.getToolCalls().isEmpty()) {
                ArrayNode names = row.putArray("toolNames");
                for (ToolCall call : message.getToolCalls()) {
                    if (names.size() == 8) break;
                    names.add(Utf8Preview.limit(call.getName(), 64));
                }
            }
            ObjectNode images = JsonNodeFactory.instance.objectNode();
            for (int n = 0;
                    n < Math.min(ImageInputValidator.MAX_IMAGES, message.getImages().size());
                    n++)
                images.put(
                        message.getMessageId() + ":" + (n + 1),
                        Utf8Preview.limit(message.getImages().get(n).getName(), 96));
            int size = Utf8Preview.bytes(row.toString()) + Utf8Preview.bytes(images.toString()) + 2;
            if (size > INSPECT_PAGE_BYTES) {
                // JSON escapes can expand control characters sixfold. Keep the row pageable.
                row.put("content", Utf8Preview.limit(message.getContent(), 256));
                row.remove("toolNames");
                size = Utf8Preview.bytes(row.toString()) + Utf8Preview.bytes(images.toString()) + 2;
            }
            if (bytes + size > INSPECT_PAGE_BYTES) break;
            rows.addFirst(row);
            attachments.setAll(images);
            bytes += size;
            i--;
        }
        out.put("beforeMessageId", i == 0 ? null : page.get(i).getMessageId());
        if (attachments.size() > 0) out.put("imageAttachments", attachments);
        out.put("messages", rows);
        return out;
    }

    private SessionRecord decorate(SessionRecord session) {
        session.setRunnerId(runnerId(session.getSessionId()));
        return session;
    }
}

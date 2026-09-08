package io.github.differentialmanifold.jagentharness.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.session.SessionDetails;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsageStore;
import io.github.differentialmanifold.jagentharness.core.usage.NoopModelCallUsageStore;
import io.github.differentialmanifold.jagentharness.spring.web.dto.CreateSessionRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.DeleteSessionRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.RenameSessionRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.SessionDetailsRequest;
import java.util.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionManager sessionManager;
    private final WorkspaceRootResolver workspaceRootResolver;
    private final ModelCallUsageStore modelCallUsageStore;

    public SessionController(
            SessionManager sessionManager, WorkspaceRootResolver workspaceRootResolver) {
        this(sessionManager, workspaceRootResolver, new NoopModelCallUsageStore());
    }

    public SessionController(
            SessionManager sessionManager,
            WorkspaceRootResolver workspaceRootResolver,
            ModelCallUsageStore modelCallUsageStore) {
        this.sessionManager = sessionManager;
        this.workspaceRootResolver = workspaceRootResolver;
        this.modelCallUsageStore =
                modelCallUsageStore == null ? new NoopModelCallUsageStore() : modelCallUsageStore;
    }

    @PostMapping
    public SessionRecord create(@RequestBody(required = false) CreateSessionRequest request) {
        String title = request == null ? null : request.getTitle();
        String workspacePath = request == null ? null : request.getWorkspacePath();
        String normalizedWorkspacePath =
                workspaceRootResolver.normalizeWorkspacePath(workspacePath);
        String projectName = request == null ? null : request.getProjectName();
        String projectId = request == null ? null : request.getProjectId();
        return sessionManager.createSession(title, normalizedWorkspacePath, projectName, projectId);
    }

    @GetMapping
    public List<SessionRecord> list() {
        return sessionManager.listSessions();
    }

    @PostMapping("/detail")
    public Map<String, Object> detail(@RequestBody SessionDetailsRequest request) {
        String sessionId = requireSessionId(request == null ? null : request.getSessionId());
        SessionDetails details = sessionManager.getDetails(sessionId);
        details.setLatestUsage(modelCallUsageStore.findLatestBySessionId(sessionId));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session", details.getSession());
        response.put("latestUsage", details.getLatestUsage());
        List<Object> events = new ArrayList<>();
        ObjectMapper json = new ObjectMapper();
        for (io.github.differentialmanifold.jagentharness.core.event.AgentEvent event :
                details.getEvents()) events.add(ProtocolMapper.event(event, json));
        response.put("events", events);
        return response;
    }

    @PostMapping("/rename")
    public SessionRecord rename(@RequestBody RenameSessionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("sessionId and title are required");
        }
        return sessionManager.renameSession(
                requireSessionId(request.getSessionId()), request.getTitle());
    }

    @PostMapping("/delete")
    public void delete(@RequestBody DeleteSessionRequest request) {
        sessionManager.deleteSession(
                requireSessionId(request == null ? null : request.getSessionId()));
    }

    private String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        return sessionId.trim();
    }
}

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RunnerService runners;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore personal;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.differentialmanifold.jagentharness.spring.personal.PersonalAgentService
            agents;

    private SessionRecord decorate(SessionRecord session) {
        if (runners != null) session.setRunnerId(runners.binding(session.getSessionId()));
        return session;
    }

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

    public SessionRecord create(@RequestBody(required = false) CreateSessionRequest request) {
        return createForOwner(request, null);
    }

    @PostMapping
    public SessionRecord createForOwner(
            @RequestBody(required = false) CreateSessionRequest request,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                    String ownerAgentId) {
        if (ownerAgentId != null && ownerAgentId.isEmpty()) ownerAgentId = null;
        if (request != null && runners != null) {
            io.github.differentialmanifold.jagentharness.protocol.RunnerProject existing =
                    runners.project(request.getProjectId());
            if (existing != null
                    || (request.getRunnerId() != null && !request.getRunnerId().isEmpty())) {
                return decorate(runners.createSession(request, existing, ownerAgentId));
            }
        }
        String title = request == null ? null : request.getTitle();
        String workspacePath = request == null ? null : request.getWorkspacePath();
        String normalizedWorkspacePath =
                workspaceRootResolver.normalizeWorkspacePath(workspacePath);
        String projectName = request == null ? null : request.getProjectName();
        String projectId = request == null ? null : request.getProjectId();
        return sessionManager.createSession(
                title, normalizedWorkspacePath, projectName, projectId, ownerAgentId);
    }

    public List<SessionRecord> list() {
        return listForOwner(null);
    }

    @GetMapping
    public List<SessionRecord> listForOwner(
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                    String ownerAgentId) {
        final String owner = ownerAgentId == null || ownerAgentId.isEmpty() ? null : ownerAgentId;
        List<SessionRecord> sessions =
                sessionManager.listSessions().stream()
                        .filter(s -> Objects.equals(owner, s.getOwnerAgentId()))
                        .collect(java.util.stream.Collectors.toList());
        for (SessionRecord session : sessions) decorate(session);
        return sessions;
    }

    @PostMapping("/detail")
    public Map<String, Object> detail(@RequestBody SessionDetailsRequest request) {
        String sessionId = requireSessionId(request == null ? null : request.getSessionId());
        SessionDetails details = sessionManager.getDetails(sessionId);
        details.setLatestUsage(modelCallUsageStore.findLatestBySessionId(sessionId));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session", decorate(details.getSession()));
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
        io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.Conversation
                conversation =
                        personal == null ? null : personal.conversation(request.getSessionId());
        if (conversation != null && agents != null) {
            agents.renameConversation(
                    conversation.agentId, conversation.sessionId, request.getTitle());
            return decorate(sessionManager.requireSession(conversation.sessionId));
        }
        return decorate(
                sessionManager.renameSession(
                        requireSessionId(request.getSessionId()), request.getTitle()));
    }

    @PostMapping("/delete")
    public void delete(@RequestBody DeleteSessionRequest request) {
        String id = requireSessionId(request == null ? null : request.getSessionId());
        io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.Conversation
                conversation = personal == null ? null : personal.conversation(id);
        if (conversation != null && agents != null)
            agents.deleteConversation(conversation.agentId, id);
        else sessionManager.deleteSession(id);
    }

    private String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        return sessionId.trim();
    }
}

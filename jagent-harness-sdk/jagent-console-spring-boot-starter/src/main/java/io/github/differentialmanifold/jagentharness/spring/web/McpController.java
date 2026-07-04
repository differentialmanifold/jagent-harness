package io.github.differentialmanifold.jagentharness.spring.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpConfigEntry;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpConfigSnapshot;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpConfigurationManager;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpRuntime;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpServerRuntimeStatus;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpTestResult;
import io.github.differentialmanifold.jagentharness.spring.web.dto.McpConfigRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.McpConfigResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.McpServerResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.McpTestRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpConfigurationManager configurationManager;
    private final McpRuntime runtime;
    private final SessionManager sessionManager;

    public McpController(McpConfigurationManager configurationManager,
                         McpRuntime runtime,
                         SessionManager sessionManager) {
        this.configurationManager = configurationManager;
        this.runtime = runtime;
        this.sessionManager = sessionManager;
    }

    @GetMapping("/config")
    public McpConfigResponse config(@RequestParam(required = false) String sessionId,
                                    @RequestParam(required = false) String scope) {
        return response(session(sessionId), scope, false);
    }

    @PutMapping("/config")
    public McpConfigResponse save(@RequestBody McpConfigRequest request,
                                  @RequestParam(required = false) String sessionId,
                                  @RequestParam(required = false) String scope) {
        if (request == null || request.getContent() == null) {
            throw new IllegalArgumentException("MCP configuration content is required");
        }
        SessionRecord session = session(sessionId);
        configurationManager.saveDatabase(scope(scope, session), request.getContent());
        return response(session, scope, true);
    }

    @DeleteMapping("/config")
    public McpConfigResponse delete(@RequestParam(required = false) String sessionId,
                                    @RequestParam(required = false) String scope) {
        SessionRecord session = session(sessionId);
        configurationManager.deleteDatabase(scope(scope, session));
        return response(session, scope, true);
    }

    @PostMapping("/test")
    public McpTestResult test(@RequestBody McpTestRequest request) {
        if (request == null || request.getName() == null || request.getConfig() == null) {
            throw new IllegalArgumentException("MCP server name and config are required");
        }
        return runtime.test(request.getName(), request.getConfig());
    }

    private McpConfigResponse response(SessionRecord session, String requestedScope, boolean restartRequired) {
        String projectId = session == null ? null : session.getProjectId();
        KnowledgeScope selectedScope = scope(requestedScope, session);
        String effectiveProjectId = selectedScope.isGlobal() ? null : projectId;
        McpConfigSnapshot snapshot = configurationManager.currentSnapshot(
                effectiveProjectId,
                selectedScope);
        Map<String, McpServerRuntimeStatus> statuses = runtime.statuses(effectiveProjectId);
        List<McpServerResponse> servers = new ArrayList<McpServerResponse>();
        for (Map.Entry<String, McpConfigEntry> entry : snapshot.getEffectiveServers().entrySet()) {
            McpServerRuntimeStatus status = statuses.get(entry.getKey());
            servers.add(new McpServerResponse(
                    entry.getKey(),
                    entry.getValue().getConfig(),
                    entry.getValue().getSource(),
                    entry.getValue().getOverriddenSources(),
                    status == null ? "not_loaded" : status.getStatus(),
                    status == null ? null : status.getError(),
                    status == null ? null : status.getProtocolVersion(),
                    status == null ? Collections.<String>emptyList() : status.getTools(),
                    status == null ? Collections.<String>emptyList() : status.getAvailableTools()));
        }
        return new McpConfigResponse(
                snapshot.getDatabaseConfig(),
                servers,
                restartRequired);
    }

    private SessionRecord session(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        return sessionManager.requireSession(sessionId.trim());
    }

    private KnowledgeScope scope(String scope, SessionRecord session) {
        if (scope == null || scope.trim().isEmpty() || "global".equalsIgnoreCase(scope.trim())) {
            return KnowledgeScope.global();
        }
        if (!"project".equalsIgnoreCase(scope.trim())) {
            throw new IllegalArgumentException("Unsupported MCP scope: " + scope);
        }
        if (session == null) {
            throw new IllegalArgumentException("sessionId is required for project MCP scope");
        }
        return KnowledgeScope.project(session.getProjectId());
    }
}

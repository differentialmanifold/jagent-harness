package io.github.differentialmanifold.jagentharness.spring.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpConfigEntry;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpConfigurationManager;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpRuntime;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpScopeConfigSnapshot;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpServerRuntimeStatus;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpTestResult;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpToolCallResult;
import io.github.differentialmanifold.jagentharness.spring.web.dto.McpConfigRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.McpConfigResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.McpServerResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.McpTestRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.McpToolCallRequest;
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
        return response(session(sessionId), scope);
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
        return response(session, scope);
    }

    @DeleteMapping("/config")
    public McpConfigResponse delete(@RequestParam(required = false) String sessionId,
                                    @RequestParam(required = false) String scope) {
        SessionRecord session = session(sessionId);
        configurationManager.deleteDatabase(scope(scope, session));
        return response(session, scope);
    }

    @PostMapping("/test")
    public McpTestResult test(@RequestBody McpTestRequest request) {
        if (request == null || request.getName() == null || request.getConfig() == null) {
            throw new IllegalArgumentException("MCP server name and config are required");
        }
        return runtime.test(request.getName(), request.getConfig());
    }

    @PostMapping("/call")
    public McpToolCallResult call(@RequestBody McpToolCallRequest request) {
        if (request == null
                || request.getName() == null
                || request.getConfig() == null
                || request.getToolName() == null
                || request.getToolName().trim().isEmpty()) {
            throw new IllegalArgumentException("MCP server, configuration, and tool name are required");
        }
        if (request.getArguments() != null && !request.getArguments().isObject()) {
            throw new IllegalArgumentException("MCP tool arguments must be a JSON object");
        }
        return runtime.call(
                request.getName(),
                request.getConfig(),
                request.getToolName().trim(),
                request.getArguments());
    }

    private McpConfigResponse response(SessionRecord session, String requestedScope) {
        String projectId = session == null ? null : session.getProjectId();
        KnowledgeScope selectedScope = scope(requestedScope, session);
        String effectiveProjectId = selectedScope.isGlobal() ? null : projectId;
        McpScopeConfigSnapshot snapshot = configurationManager.scopeSnapshot(selectedScope);
        Map<String, McpServerRuntimeStatus> statuses = runtime.statuses(effectiveProjectId);
        List<McpServerResponse> servers = new ArrayList<McpServerResponse>();
        for (Map.Entry<String, McpConfigEntry> entry : snapshot.getServers().entrySet()) {
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
                    status == null ? Collections.<String>emptyList() : status.getAvailableTools(),
                    status == null ? Collections.emptyList() : status.getToolDetails()));
        }
        return new McpConfigResponse(
                snapshot.getDatabaseConfig(),
                servers);
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

package io.github.differentialmanifold.jagentharness.spring.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpConfigDocument;
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
    private final WorkspaceRootResolver workspaceRootResolver;

    public McpController(McpConfigurationManager configurationManager,
                         McpRuntime runtime,
                         SessionManager sessionManager,
                         WorkspaceRootResolver workspaceRootResolver) {
        this.configurationManager = configurationManager;
        this.runtime = runtime;
        this.sessionManager = sessionManager;
        this.workspaceRootResolver = workspaceRootResolver;
    }

    @GetMapping("/config")
    public McpConfigResponse config(@RequestParam(required = false) String sessionId) {
        return response(workspaceRoot(sessionId), false);
    }

    @PutMapping("/config")
    public McpConfigResponse save(@RequestBody McpConfigRequest request,
                                  @RequestParam(required = false) String sessionId) {
        McpConfigDocument document = new McpConfigDocument();
        document.setMcpServers(request.getMcpServers());
        configurationManager.saveDatabase(document, request.getExpectedContentHash());
        return response(workspaceRoot(sessionId), true);
    }

    @PostMapping("/test")
    public McpTestResult test(@RequestBody McpTestRequest request) {
        if (request == null || request.getName() == null || request.getConfig() == null) {
            throw new IllegalArgumentException("MCP server name and config are required");
        }
        return runtime.test(request.getName(), request.getConfig());
    }

    private McpConfigResponse response(Path workspaceRoot, boolean restartRequired) {
        McpConfigSnapshot snapshot = configurationManager.currentSnapshot(workspaceRoot);
        Map<String, McpServerRuntimeStatus> statuses = runtime.statuses(workspaceRoot);
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
                    status == null ? Collections.<String>emptyList() : status.getTools()));
        }
        return new McpConfigResponse(
                snapshot.getDatabaseContentHash(),
                snapshot.getDatabaseServers(),
                servers,
                restartRequired);
    }

    private Path workspaceRoot(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        SessionRecord session = sessionManager.requireSession(sessionId.trim());
        return workspaceRootResolver.resolveWorkspaceRoot(session.getWorkspacePath());
    }
}

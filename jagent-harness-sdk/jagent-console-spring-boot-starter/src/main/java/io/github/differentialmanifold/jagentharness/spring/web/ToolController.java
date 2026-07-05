package io.github.differentialmanifold.jagentharness.spring.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.core.tool.KnowledgeFileToolConfiguration;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalMode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.core.tool.ToolSelectionSnapshot;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ToolCallRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ToolCallResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ToolConfigRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ToolConfigResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ToolInfoResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final KnowledgeFileToolConfiguration toolConfiguration;
    private final SessionManager sessionManager;
    private final ToolContextFactory toolContextFactory;
    private final ObjectMapper objectMapper;

    public ToolController(ToolRegistry toolRegistry) {
        this(toolRegistry, null, null, null, new ObjectMapper());
    }

    public ToolController(ToolRegistry toolRegistry,
                          KnowledgeFileToolConfiguration toolConfiguration,
                          SessionManager sessionManager,
                          ToolContextFactory toolContextFactory,
                          ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.toolConfiguration = toolConfiguration;
        this.sessionManager = sessionManager;
        this.toolContextFactory = toolContextFactory;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<ToolInfoResponse> list() {
        List<ToolInfoResponse> tools = new ArrayList<ToolInfoResponse>();
        for (ToolDefinition tool : toolRegistry.all()) {
            tools.add(new ToolInfoResponse(tool.getName(), tool.getDescription(), tool.getParametersSchema()));
        }
        return tools;
    }

    @GetMapping("/config")
    public ToolConfigResponse config() {
        return configResponse(selection());
    }

    @PutMapping("/config")
    public ToolConfigResponse save(@RequestBody ToolConfigRequest request) {
        if (request == null || request.getEnabledTools() == null) {
            throw new IllegalArgumentException("enabledTools is required");
        }
        KnowledgeFileToolConfiguration configuration = requireConfiguration();
        Set<String> requested = normalize(request.getEnabledTools());
        List<String> ordered = new ArrayList<String>();
        for (ToolDefinition tool : toolRegistry.registeredTools()) {
            if (requested.remove(tool.getName())) {
                ordered.add(tool.getName());
            }
        }
        if (!requested.isEmpty()) {
            throw new IllegalArgumentException("Unknown built-in tools: " + String.join(", ", requested));
        }
        return configResponse(configuration.save(ordered));
    }

    @DeleteMapping("/config")
    public ToolConfigResponse delete() {
        requireConfiguration().delete();
        return configResponse(ToolSelectionSnapshot.defaults());
    }

    @PostMapping("/call")
    public ToolCallResponse call(@RequestBody ToolCallRequest request) throws Exception {
        if (request == null || request.getToolName() == null || request.getToolName().trim().isEmpty()) {
            throw new IllegalArgumentException("toolName is required");
        }
        JsonNode arguments = request.getArguments();
        if (arguments != null && !arguments.isObject()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
        String toolName = request.getToolName().trim();
        ToolDefinition tool = toolRegistry.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Built-in tool not found: " + toolName);
        }
        if (toolContextFactory == null) {
            throw new IllegalStateException("ToolContextFactory is required for tool debugging");
        }
        SessionRecord session = session(request.getSessionId());
        AgentRunOptions options = AgentRunOptions.builder()
                .approvalMode(ToolApprovalMode.FULL_ACCESS)
                .build();
        String toolCallId = Ids.newId("tool");
        ToolContext context = toolContextFactory
                .create(session, Ids.newId("debug"), options)
                .forToolCall(toolCallId, toolName);
        ToolExecutionResult result = tool.execute(
                context,
                arguments == null ? objectMapper.createObjectNode() : arguments);
        return new ToolCallResponse(result == null ? "" : result.getContent());
    }

    private ToolConfigResponse configResponse(ToolSelectionSnapshot selection) {
        List<String> enabled = new ArrayList<String>();
        List<ToolInfoResponse> tools = new ArrayList<ToolInfoResponse>();
        for (ToolDefinition tool : toolRegistry.registeredTools()) {
            boolean available = selection.isEnabled(tool.getName());
            if (available) {
                enabled.add(tool.getName());
            }
            tools.add(new ToolInfoResponse(
                    tool.getName(),
                    tool.getDescription(),
                    tool.getParametersSchema(),
                    available));
        }
        return new ToolConfigResponse(selection.isConfigured(), enabled, tools);
    }

    private ToolSelectionSnapshot selection() {
        return toolConfiguration == null ? ToolSelectionSnapshot.defaults() : toolConfiguration.load();
    }

    private KnowledgeFileToolConfiguration requireConfiguration() {
        if (toolConfiguration == null) {
            throw new IllegalStateException("KnowledgeFileStore is required for built-in tool configuration");
        }
        return toolConfiguration;
    }

    private Set<String> normalize(Collection<String> names) {
        Set<String> normalized = new LinkedHashSet<String>();
        for (String name : names) {
            String value = name == null ? "" : name.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Enabled tool names must not be blank");
            }
            normalized.add(value);
        }
        return normalized;
    }

    private SessionRecord session(String sessionId) {
        String normalized = sessionId == null ? "" : sessionId.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (sessionManager == null) {
            throw new IllegalStateException("SessionManager is required for contextual tool debugging");
        }
        return sessionManager.requireSession(normalized);
    }
}

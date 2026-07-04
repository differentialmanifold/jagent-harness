package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolProvider;
import io.github.differentialmanifold.jagentharness.mcp.McpClient;
import io.github.differentialmanifold.jagentharness.mcp.McpRemoteTool;
import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;
import io.github.differentialmanifold.jagentharness.mcp.McpToolDescriptor;
import org.springframework.beans.factory.SmartInitializingSingleton;

public class McpRuntime implements ToolProvider, AutoCloseable, SmartInitializingSingleton {

    private static final String DEFAULT_SCOPE = "@default";

    private final McpConfigurationManager configurationManager;
    private final ObjectMapper objectMapper;
    private final Map<String, Scope> scopes = new LinkedHashMap<String, Scope>();
    private volatile boolean initialized;

    public McpRuntime(McpConfigurationManager configurationManager, ObjectMapper objectMapper) {
        this.configurationManager = configurationManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterSingletonsInstantiated() {
        initialize();
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        configurationManager.initialize();
        scope(null);
        initialized = true;
    }

    @Override
    public Collection<ToolDefinition> listTools(AgentContext context) {
        ensureInitialized();
        String projectId = context == null ? null : context.getProjectId();
        return scope(projectId).tools;
    }

    public synchronized Map<String, McpServerRuntimeStatus> statuses(String projectId) {
        Scope scope = scopes.get(scopeKey(projectId));
        if (scope == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, McpServerRuntimeStatus>(scope.statuses));
    }

    public McpTestResult test(String name, McpServerConfig input) {
        McpClient client = null;
        try {
            McpServerConfig config = configurationManager.resolve(name, input);
            client = new McpClient(config, objectMapper);
            List<McpToolDescriptor> descriptors = client.listTools();
            List<String> names = new ArrayList<String>();
            for (McpToolDescriptor descriptor : descriptors) {
                names.add(descriptor.getName());
            }
            return new McpTestResult(true, null, client.getNegotiatedProtocolVersion(), names);
        } catch (Exception e) {
            return new McpTestResult(false, safeMessage(e), null, Collections.<String>emptyList());
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    private synchronized Scope scope(String projectId) {
        String key = scopeKey(projectId);
        Scope existing = scopes.get(key);
        if (existing != null) {
            return existing;
        }
        Scope loaded = loadScope(projectId);
        scopes.put(key, loaded);
        return loaded;
    }

    private Scope loadScope(String projectId) {
        McpConfigSnapshot snapshot = configurationManager.runtimeSnapshot(projectId);
        List<ToolDefinition> tools = new ArrayList<ToolDefinition>();
        Map<String, McpServerRuntimeStatus> statuses = new LinkedHashMap<String, McpServerRuntimeStatus>();
        List<McpClient> clients = new ArrayList<McpClient>();
        Map<String, String> modelNames = new LinkedHashMap<String, String>();

        for (Map.Entry<String, McpConfigEntry> entry : snapshot.getEffectiveServers().entrySet()) {
            String serverName = entry.getKey();
            McpServerConfig rawConfig = entry.getValue().getConfig();
            if (!rawConfig.isEnabled()) {
                statuses.put(serverName, new McpServerRuntimeStatus(
                        "disabled", null, null, Collections.<String>emptyList()));
                continue;
            }
            McpClient client = null;
            try {
                McpServerConfig config = configurationManager.resolve(serverName, rawConfig);
                client = new McpClient(config, objectMapper);
                List<McpToolDescriptor> descriptors = client.listTools();
                List<String> remoteNames = new ArrayList<String>();
                List<String> availableNames = new ArrayList<String>();
                List<ToolDefinition> serverTools = new ArrayList<ToolDefinition>();
                Map<String, String> serverModelNames = new LinkedHashMap<String, String>();
                for (McpToolDescriptor descriptor : descriptors) {
                    availableNames.add(descriptor.getName());
                    if (!isToolEnabled(config, descriptor.getName())) {
                        continue;
                    }
                    McpRemoteTool tool = new McpRemoteTool(serverName, descriptor, client, objectMapper);
                    String qualifiedName = serverName + "/" + descriptor.getName();
                    String previous = modelNames.get(tool.getName());
                    if (previous == null) {
                        previous = serverModelNames.put(tool.getName(), qualifiedName);
                    }
                    if (previous != null) {
                        throw new IllegalStateException("MCP tool name collision between " + previous
                                + " and " + qualifiedName);
                    }
                    serverTools.add(tool);
                    remoteNames.add(descriptor.getName());
                }
                modelNames.putAll(serverModelNames);
                clients.add(client);
                tools.addAll(serverTools);
                statuses.put(serverName, new McpServerRuntimeStatus(
                        "available", null, client.getNegotiatedProtocolVersion(), remoteNames, availableNames));
            } catch (Exception e) {
                if (client != null) {
                    client.close();
                }
                statuses.put(serverName, new McpServerRuntimeStatus(
                        "unavailable", safeMessage(e), null, Collections.<String>emptyList()));
            }
        }
        return new Scope(tools, statuses, clients);
    }

    private boolean isToolEnabled(McpServerConfig config, String toolName) {
        return config.getEnabledTools() == null || config.getEnabledTools().contains(toolName);
    }

    private String scopeKey(String projectId) {
        return projectId == null || projectId.trim().isEmpty() ? DEFAULT_SCOPE : projectId.trim();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

    @Override
    public synchronized void close() {
        for (Scope scope : scopes.values()) {
            for (McpClient client : scope.clients) {
                client.close();
            }
        }
        scopes.clear();
        initialized = false;
    }

    private static class Scope {
        private final Collection<ToolDefinition> tools;
        private final Map<String, McpServerRuntimeStatus> statuses;
        private final List<McpClient> clients;

        private Scope(List<ToolDefinition> tools,
                      Map<String, McpServerRuntimeStatus> statuses,
                      List<McpClient> clients) {
            this.tools = Collections.unmodifiableList(new ArrayList<ToolDefinition>(tools));
            this.statuses = Collections.unmodifiableMap(new LinkedHashMap<String, McpServerRuntimeStatus>(statuses));
            this.clients = new ArrayList<McpClient>(clients);
        }
    }
}

package io.github.differentialmanifold.jagentharness.core.agent;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class AgentContext {

    private final String sessionId;
    private final String runId;
    private final String turnId;
    private final String traceId;
    private final String projectId;
    private final Path workspaceRoot;
    private final Path configRoot;
    private final Map<String, Object> attributes;

    public AgentContext(String sessionId, String runId, String turnId) {
        this(sessionId, runId, turnId, null, null, null, Collections.<String, Object>emptyMap());
    }

    public AgentContext(String sessionId,
                        String runId,
                        String turnId,
                        String traceId,
                        Path workspaceRoot,
                        Path configRoot,
                        Map<String, Object> attributes) {
        this(sessionId, runId, turnId, traceId, workspaceRoot, configRoot, attributes, null);
    }

    public AgentContext(String sessionId,
                        String runId,
                        String turnId,
                        String traceId,
                        Path workspaceRoot,
                        Path configRoot,
                        Map<String, Object> attributes,
                        String projectId) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.traceId = traceId;
        this.projectId = projectId;
        this.workspaceRoot = normalize(workspaceRoot);
        this.configRoot = normalize(configRoot);
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        if (attributes != null) {
            copy.putAll(attributes);
        }
        this.attributes = Collections.unmodifiableMap(copy);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getProjectId() {
        return projectId;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public Path getConfigRoot() {
        return configRoot;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null || !type.isInstance(value)) {
            return null;
        }
        return type.cast(value);
    }

    private Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}

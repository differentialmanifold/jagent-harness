package io.github.differentialmanifold.jagentharness.core.tool;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;

public class ToolContext extends AgentContext {

    public ToolContext(String sessionId, String turnId) {
        this(sessionId, turnId, null, null, null, Collections.<String, Object>emptyMap());
    }

    public ToolContext(String sessionId, String turnId, Path workspaceRoot) {
        this(sessionId, turnId, null, workspaceRoot, null, Collections.<String, Object>emptyMap());
    }

    public ToolContext(String sessionId,
                       String turnId,
                       String traceId,
                       Map<String, Object> attributes) {
        this(sessionId, turnId, traceId, null, null, attributes);
    }

    public ToolContext(String sessionId,
                       String turnId,
                       String traceId,
                       Path workspaceRoot,
                       Path configRoot,
                       Map<String, Object> attributes) {
        super(sessionId, turnId, traceId, workspaceRoot, configRoot, attributes);
    }
}

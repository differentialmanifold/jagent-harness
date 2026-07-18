package io.github.differentialmanifold.jagentharness.core.conversation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;

public class ConversationContextRequest {

    private final String sessionId;
    private final String runId;
    private final String turnId;
    private final String systemPrompt;
    private final List<AgentMessage> messages;
    private final Collection<ToolDefinition> tools;
    private final ModelProvider provider;
    private final StopSignal stopSignal;

    public ConversationContextRequest(String sessionId,
                                      String runId,
                                      String turnId,
                                      String systemPrompt,
                                      List<AgentMessage> messages,
                                      Collection<ToolDefinition> tools,
                                      ModelProvider provider) {
        this(sessionId, runId, turnId, systemPrompt, messages, tools, provider, StopSignal.none());
    }

    public ConversationContextRequest(String sessionId,
                                      String runId,
                                      String turnId,
                                      String systemPrompt,
                                      List<AgentMessage> messages,
                                      Collection<ToolDefinition> tools,
                                      ModelProvider provider,
                                      StopSignal stopSignal) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.systemPrompt = systemPrompt;
        this.messages = messages == null
                ? Collections.<AgentMessage>emptyList()
                : Collections.unmodifiableList(new ArrayList<AgentMessage>(messages));
        this.tools = tools == null
                ? Collections.<ToolDefinition>emptyList()
                : Collections.unmodifiableCollection(new ArrayList<ToolDefinition>(tools));
        this.provider = provider;
        this.stopSignal = stopSignal == null ? StopSignal.none() : stopSignal;
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

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<AgentMessage> getMessages() {
        return messages;
    }

    public Collection<ToolDefinition> getTools() {
        return tools;
    }

    public ModelProvider getProvider() {
        return provider;
    }

    public StopSignal getStopSignal() {
        return stopSignal;
    }
}

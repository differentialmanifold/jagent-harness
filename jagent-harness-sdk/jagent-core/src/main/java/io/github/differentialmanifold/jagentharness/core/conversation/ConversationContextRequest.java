package io.github.differentialmanifold.jagentharness.core.conversation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;

public class ConversationContextRequest {

    private final String sessionId;
    private final String turnId;
    private final String systemPrompt;
    private final List<AgentMessage> messages;
    private final Collection<ToolDefinition> tools;
    private final ModelProvider provider;

    public ConversationContextRequest(String sessionId,
                                      String turnId,
                                      String systemPrompt,
                                      List<AgentMessage> messages,
                                      Collection<ToolDefinition> tools,
                                      ModelProvider provider) {
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.systemPrompt = systemPrompt;
        this.messages = messages == null
                ? Collections.<AgentMessage>emptyList()
                : Collections.unmodifiableList(new ArrayList<AgentMessage>(messages));
        this.tools = tools == null
                ? Collections.<ToolDefinition>emptyList()
                : Collections.unmodifiableCollection(new ArrayList<ToolDefinition>(tools));
        this.provider = provider;
    }

    public String getSessionId() {
        return sessionId;
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
}

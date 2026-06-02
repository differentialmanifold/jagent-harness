package io.github.differentialmanifold.jagentharness.core.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;

public class ConversationContext {

    private final String systemPrompt;
    private final List<AgentMessage> messages;

    public ConversationContext(String systemPrompt, List<AgentMessage> messages) {
        this.systemPrompt = systemPrompt;
        this.messages = messages == null
                ? Collections.<AgentMessage>emptyList()
                : Collections.unmodifiableList(new ArrayList<AgentMessage>(messages));
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<AgentMessage> getMessages() {
        return messages;
    }
}

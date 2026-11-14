package io.github.differentialmanifold.jagentharness.core.conversation;

public interface ConversationContextManager {

    default ConversationContextManager forSettings(
            io.github.differentialmanifold.jagentharness.core.agent.AgentSettings settings) {
        return this;
    }

    ConversationContext prepare(ConversationContextRequest request);
}

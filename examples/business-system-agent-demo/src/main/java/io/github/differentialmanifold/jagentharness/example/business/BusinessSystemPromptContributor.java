package io.github.differentialmanifold.jagentharness.example.business;

import io.github.differentialmanifold.jagentharness.core.prompt.PromptContext;
import io.github.differentialmanifold.jagentharness.core.prompt.SystemPromptContributor;
import org.springframework.stereotype.Component;

@Component
public class BusinessSystemPromptContributor implements SystemPromptContributor {

    @Override
    public String contribute(PromptContext context) {
        return "You are embedded in a customer operations business system. "
                + "When answering operational questions, prefer the registered business tools over guessing from memory. "
                + "Use customer_lookup for account context, refund_policy_check for refund decisions, "
                + "and ticket_create when a follow-up case must be tracked. "
                + "Explain results in concise business language and include the next action.";
    }
}

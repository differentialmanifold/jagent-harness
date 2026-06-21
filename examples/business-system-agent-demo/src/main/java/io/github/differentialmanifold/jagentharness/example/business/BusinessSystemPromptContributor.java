package io.github.differentialmanifold.jagentharness.example.business;

import io.github.differentialmanifold.jagentharness.core.prompt.PromptContext;
import io.github.differentialmanifold.jagentharness.core.prompt.SystemPromptContributor;
import org.springframework.stereotype.Component;

@Component
public class BusinessSystemPromptContributor implements SystemPromptContributor {

    @Override
    public String contribute(PromptContext context) {
        return "You are embedded in a demo shopping business system. "
                + "For shopping requests, use the Shopping Assistant skill to decide the workflow. "
                + "Prefer the registered business tools over guessing from memory: product_search for candidates, "
                + "inventory_check for stock and delivery, and cart_add only when the user asks to buy or add an item. "
                + "Do not create orders or process payments.";
    }
}

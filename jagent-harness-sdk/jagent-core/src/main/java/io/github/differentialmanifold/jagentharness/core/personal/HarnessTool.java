package io.github.differentialmanifold.jagentharness.core.personal;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import java.util.function.Supplier;

/** mainbot's user-scoped Console management entry point. */
final class HarnessTool extends PersonalTool {
    private final HarnessOperations operations;

    HarnessTool(
            Supplier<PersonalAgentService> services,
            PersonalAgentStore store,
            ObjectMapper json,
            HarnessOperations operations) {
        super(
                "harness",
                "Manage this user's JAgentHarness settings and resources on the user's behalf. Describe a resource to discover operations and argument schemas, then execute an operation. These are the same business services used by Console. Never change default model settings autonomously or request secrets in chat.",
                services,
                store,
                json);
        this.operations = operations;
        action(
                "describe",
                "List resources, or describe one resource's operations and arguments.",
                fields(
                        "resource",
                        text("Optional resource, such as models. Omit to list resources.")),
                new String[] {});
        action(
                "execute",
                "Execute a registered operation with exactly the arguments described for it.",
                fields(
                        "operation",
                        text("Operation name returned by describe, such as models.add."),
                        "arguments",
                        json.createObjectNode()
                                .put("type", "object")
                                .put("additionalProperties", true)),
                new String[] {},
                "operation",
                "arguments");
    }

    @Override
    boolean supports(PersonalAgentStore.Profile profile) {
        return PersonalAgentService.MAINBOT_ID.equals(profile.id);
    }

    Object run(String id, ToolContext context, String action, JsonNode args) throws Exception {
        if ("describe".equals(action))
            return operations.describe(args.path("resource").asText(null));
        return operations.execute(context, required(args, "operation"), args.get("arguments"));
    }
}

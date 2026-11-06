package io.github.differentialmanifold.jagentharness.core.personal;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import java.util.function.Supplier;

public final class MemoryTool extends PersonalTool {
    private final PersonalAgentKnowledge knowledge;

    MemoryTool(
            Supplier<PersonalAgentService> s,
            PersonalAgentStore store,
            ObjectMapper json,
            PersonalAgentKnowledge k) {
        super(
                "memory",
                "Maintain durable preferences, facts and verified lessons. Current memory is already in your prompt; read before editing for its latest revision.",
                s,
                store,
                json);
        knowledge = k;
        action(
                "read",
                "Read memory and shared profile revision.",
                fields(),
                new String[] {"memory_read"});
        action(
                "add",
                "Append one durable entry; preserve unrelated memory.",
                editFields(
                        "category",
                        choice("Category.", "preference", "fact", "lesson"),
                        "content",
                        text("Durable entry; never credentials.")),
                new String[] {"memory_add"},
                "category",
                "content",
                "reason",
                "revision");
        action(
                "edit",
                "Replace exactly one matching span. Empty content removes it.",
                editFields(
                        "oldText",
                        text("Exact unique span from current memory."),
                        "content",
                        text("Replacement; empty string deletes the span.")),
                new String[] {"memory_patch", "memory_remove"},
                "oldText",
                "content",
                "reason",
                "revision");
    }

    Object run(String id, ToolContext c, String action, JsonNode args) {
        if ("read".equals(action)) return knowledge.memory(id);
        return knowledge.edit(id, "memory." + action, args, c.getSessionId(), "AGENT");
    }
}

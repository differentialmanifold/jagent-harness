package io.github.differentialmanifold.jagentharness.spring.personal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import org.springframework.beans.factory.ObjectProvider;

public final class TasksTool extends PersonalTool {
    TasksTool(ObjectProvider<PersonalAgentService> s, PersonalAgentStore store, ObjectMapper json) {
        super(
                "tasks",
                "Maintain your own plan for multi-step work. Users view tasks but request changes through Chat. Each scheduled check wakes the owning conversation once.",
                s,
                store,
                json);
        action(
                "list",
                "Read tasks with full plans, criteria, evidence and revisions. Filter or paginate when needed.",
                fields(
                        "id",
                        text("One task ID."),
                        "parentId",
                        text("Only direct children of this goal."),
                        "status",
                        choice(
                                "Filter by status.",
                                "TODO",
                                "DOING",
                                "WAITING",
                                "DONE",
                                "CANCELLED"),
                        "offset",
                        number("Default zero."),
                        "limit",
                        number("Default 50; at most 100.")),
                new String[] {"cards_list"});
        action(
                "save",
                "Create or update a task; omitted fields are preserved on update. Root tasks hold goals and plans; children hold actionable work. Set acceptance before dispatch. DONE requires evidence for criteria and completed dependencies/children; CANCELLED means deliberately abandoned, not successful. Include revision on update.",
                fields(
                        "id",
                        text("Existing task ID; omit to create."),
                        "revision",
                        number("Current task revision for updates."),
                        "title",
                        text("Goal."),
                        "status",
                        choice("Status.", "TODO", "DOING", "WAITING", "DONE", "CANCELLED"),
                        "parentId",
                        text("Parent goal task; empty string clears it."),
                        "dependsOn",
                        array(text("Task ID that must be DONE before this task can run.")),
                        "description",
                        text("Current plan, scope and constraints, up to 8000 characters."),
                        "acceptance",
                        text("Verifiable completion criteria, up to 4000 characters."),
                        "evidence",
                        array(
                                io.github.differentialmanifold.jagentharness.core.tool.support
                                        .ToolSchemas.objectSchema(
                                        json,
                                        fields(
                                                "sessionId",
                                                text("Owned project session."),
                                                "messageId",
                                                text(
                                                        "Tool output or assistant evidence message ID from projects inspect."),
                                                "note",
                                                text(
                                                        "What this output verifies; at most 2000 characters.")),
                                        "sessionId",
                                        "messageId",
                                        "note")),
                        "progress",
                        text(
                                "Concise current outcome and next action; for WAITING, explain the blocker and what will unblock it. Put detailed outputs in project history and evidence references."),
                        "sessionId",
                        text("Optional owned project session."),
                        "nextCheckAt",
                        number(
                                "UTC Unix milliseconds, at least one minute ahead; zero disables checking.")),
                new String[] {"cards_save"},
                "title",
                "status");
    }

    Object run(String id, ToolContext context, String action, JsonNode args) throws Exception {
        if ("list".equals(action)) {
            int offset = Math.max(0, args.path("offset").asInt(0)),
                    limit = Math.max(1, Math.min(100, args.path("limit").asInt(50)));
            return services.getObject().cards(id).stream()
                    .filter(c -> !args.has("id") || c.id.equals(args.get("id").asText()))
                    .filter(
                            c ->
                                    !args.has("parentId")
                                            || java.util.Objects.equals(
                                                    c.parentId, args.get("parentId").asText()))
                    .filter(
                            c ->
                                    !args.has("status")
                                            || c.status.equals(args.get("status").asText()))
                    .skip(offset)
                    .limit(limit)
                    .collect(java.util.stream.Collectors.toList());
        }
        ObjectNode body = ((ObjectNode) args).deepCopy();
        body.remove("action");
        Card old = args.has("id") ? store.card(required(args, "id")) : null;
        if (args.has("id") && (old == null || !id.equals(old.ownerId)))
            throw new IllegalArgumentException("Task not found for this Agent");
        if (old != null && !args.has("revision"))
            throw new IllegalArgumentException("revision is required for updates");
        Card c =
                old == null
                        ? json.treeToValue(body, Card.class)
                        : json.readerForUpdating(old).readValue(body);
        c.conversationId = c.conversationId == null ? context.getSessionId() : c.conversationId;
        return services.getObject()
                .saveCard(c, id, context.getAttribute("personalReplyTo", ReplyTarget.class));
    }
}

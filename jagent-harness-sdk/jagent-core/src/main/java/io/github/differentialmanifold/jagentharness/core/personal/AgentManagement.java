package io.github.differentialmanifold.jagentharness.core.personal;

import static io.github.differentialmanifold.jagentharness.core.personal.ManagementSchemas.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import java.util.*;
import java.util.function.Supplier;

/** Console-style management of the current user's Agents; delegation stays in agents. */
public final class AgentManagement {
    private AgentManagement() {}

    public static void register(
            HarnessOperations ops,
            Supplier<PersonalAgentService> services,
            Supplier<PersonalAgentStore> stores,
            Supplier<PersonalAgentKnowledge> knowledge,
            Supplier<PersonalAgentTools> tools,
            ObjectMapper json) {
        ObjectNode agentFields = fields("agentId", text("Existing Agent ID in this user's space."));
        ops.register(
                "agents.list",
                "List this user's Agents and defaults. Use the ordinary agents tool for delegation.",
                object(fields()),
                (ctx, a) -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    List<Map<String, Object>> profiles = new ArrayList<>();
                    for (Profile p : stores.get().profiles())
                        profiles.add(services.get().agentDescription(p));
                    out.put("agents", profiles);
                    out.put("defaults", services.get().defaults());
                    return out;
                });
        ops.register(
                "agents.read",
                "Read one Agent's current configuration and revision before an edit.",
                object(agentFields, "agentId"),
                (ctx, a) -> services.get().require(a.get("agentId").asText()));
        ObjectNode profileFields =
                fields(
                        "name",
                        text("Display name."),
                        "description",
                        text("Role and expertise."),
                        "instructions",
                        text("Role instructions."),
                        "enabled",
                        property("boolean", "Whether the Agent is active."),
                        "disabledTools",
                        strings(
                                "Tool/action names disabled in configuration. Read agents.tools before editing. An empty list enables all available tools."));
        ObjectNode createFields = profileFields.deepCopy();
        createFields.set(
                "runnerId",
                text("Existing Runner ID. Omit for the primary Runner, as in Console."));
        ops.register(
                "agents.create",
                "Create an Agent in the current user's space, using the same defaults as Console. This does not deploy a Runner.",
                object(createFields, "name"),
                (ctx, a) -> {
                    Profile p = json.treeToValue(a, Profile.class);
                    if (p.runnerId == null)
                        p.runnerId = services.get().defaults().get("primaryRunnerId");
                    return services.get().save(p);
                });
        ops.register(
                "agents.update",
                "Change only supplied Agent settings. Runner binding stays fixed. Read current revision and preserve unrelated settings.",
                object(
                        fields(
                                "agentId",
                                agentFields.get("agentId"),
                                "revision",
                                revision(),
                                "changes",
                                object(profileFields)),
                        "agentId",
                        "revision",
                        "changes"),
                (ctx, a) -> {
                    Profile p = services.get().require(a.get("agentId").asText());
                    if (p.revision != a.get("revision").asLong())
                        throw new IllegalArgumentException(
                                "Agent settings changed; reload before saving");
                    json.readerForUpdating(p).readValue(a.get("changes"));
                    return services.get().save(p);
                });
        ops.register(
                "agents.primary",
                "Select the user's primary Agent when requested. This does not change mainbot's identity.",
                object(
                        fields(
                                "agentId",
                                agentFields.get("agentId"),
                                "expectedPrimaryAgentId",
                                text("Current primaryAgentId from agents.list, or null.")
                                        .put("nullable", true)),
                        "agentId",
                        "expectedPrimaryAgentId"),
                (ctx, a) ->
                        services.get()
                                .selectPrimaryAgent(
                                        a.get("agentId").asText(),
                                        a.get("expectedPrimaryAgentId").asText(null)));
        ops.register(
                "agents.tools",
                "Read available tools and disabled operations for this Agent.",
                object(agentFields, "agentId"),
                (ctx, a) -> tools.get().catalog(services.get().require(a.get("agentId").asText())));
        ops.register(
                "agents.tasks",
                "Read an Agent's task board. Tasks are normally maintained by that Agent's own tasks tool.",
                object(agentFields, "agentId"),
                (ctx, a) -> services.get().cards(a.get("agentId").asText()));
        ops.register(
                "agents.conversations",
                "List an Agent's conversations.",
                object(agentFields, "agentId"),
                (ctx, a) -> services.get().conversations(a.get("agentId").asText()));
        ObjectNode chatFields = agentFields.deepCopy();
        chatFields.set("sessionId", text("Conversation ID belonging to this Agent."));
        ObjectNode historyFields = chatFields.deepCopy();
        historyFields.set("after", text("Optional history cursor from the previous page."));
        ops.register(
                "agents.history",
                "Read one page of conversation history on the user's behalf. Use the existing agents tool to send delegated work and receive asynchronous replies.",
                object(historyFields, "agentId", "sessionId"),
                (ctx, a) ->
                        services.get()
                                .historyPage(
                                        a.get("agentId").asText(),
                                        a.get("sessionId").asText(),
                                        a.path("after").asText(null),
                                        40));
        ObjectNode newChat = agentFields.deepCopy();
        newChat.set("title", text("Conversation title."));
        ops.register(
                "agents.createConversation",
                "Create an empty user-visible conversation.",
                object(newChat, "agentId", "title"),
                (ctx, a) ->
                        services.get()
                                .newConversation(
                                        a.get("agentId").asText(), a.get("title").asText()));
        ObjectNode renameChat = chatFields.deepCopy();
        renameChat.set("title", text("New title."));
        ops.register(
                "agents.renameConversation",
                "Rename an existing conversation.",
                object(renameChat, "agentId", "sessionId", "title"),
                (ctx, a) ->
                        services.get()
                                .renameConversation(
                                        a.get("agentId").asText(),
                                        a.get("sessionId").asText(),
                                        a.get("title").asText()));
        ops.register(
                "agents.deleteConversation",
                "Delete a conversation only when requested. Active chats cannot be deleted.",
                object(chatFields, "agentId", "sessionId"),
                (ctx, a) -> {
                    services.get()
                            .deleteConversation(
                                    a.get("agentId").asText(), a.get("sessionId").asText());
                    return Collections.singletonMap("deleted", a.get("sessionId").asText());
                });
        ops.register(
                "agents.skills",
                "List private, default and built-in Skills visible in this Agent's configuration.",
                object(agentFields, "agentId"),
                (ctx, a) -> knowledge.get().availableSkills(a.get("agentId").asText()));
        ObjectNode skillFields = agentFields.deepCopy();
        skillFields.set("path", text("Skill path returned by agents.skills."));
        ops.register(
                "agents.readSkill",
                "Read an available Skill and its source. Built-in Skills are read-only.",
                object(skillFields, "agentId", "path"),
                (ctx, a) ->
                        knowledge
                                .get()
                                .availableSkill(a.get("agentId").asText(), a.get("path").asText()));
        ObjectNode edit =
                fields(
                        "revision",
                        revision(),
                        "reason",
                        text("User request and reason for this edit."),
                        "action",
                        text(
                                "memory.add, memory.patch, memory.remove, skills.save, skills.patch or skills.remove."),
                        "path",
                        text("Private Skill file path, required for skills operations."),
                        "category",
                        text("preference, fact or lesson for memory.add."),
                        "oldText",
                        text("Unique exact existing span for patch/remove."),
                        "content",
                        text("New text for add, save or patch."));
        ops.register(
                "agents.editKnowledge",
                "Edit an Agent's private memory or Skill with revision checks and reversible change history. Use knowledge operations for project/default scopes.",
                object(
                        fields(
                                "agentId",
                                agentFields.get("agentId"),
                                "change",
                                object(edit, "revision", "reason", "action")),
                        "agentId",
                        "change"),
                (ctx, a) ->
                        knowledge
                                .get()
                                .edit(
                                        a.get("agentId").asText(),
                                        a.get("change").get("action").asText(),
                                        a.get("change"),
                                        null,
                                        "MAINBOT"));
        ops.register(
                "agents.changes",
                "Read recent reversible knowledge changes for an Agent.",
                object(agentFields, "agentId"),
                (ctx, a) -> knowledge.get().changes(a.get("agentId").asText(), 0));
        ops.register(
                "agents.undoKnowledge",
                "Undo a specific knowledge change requested by the user.",
                object(
                        fields(
                                "agentId",
                                agentFields.get("agentId"),
                                "changeId",
                                text("Change ID from agents.changes."),
                                "revision",
                                revision()),
                        "agentId",
                        "changeId",
                        "revision"),
                (ctx, a) ->
                        knowledge
                                .get()
                                .undo(
                                        a.get("agentId").asText(),
                                        a.get("changeId").asText(),
                                        a.get("revision").asLong()));
    }
}

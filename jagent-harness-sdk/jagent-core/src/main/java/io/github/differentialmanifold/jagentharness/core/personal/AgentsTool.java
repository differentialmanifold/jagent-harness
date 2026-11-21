package io.github.differentialmanifold.jagentharness.core.personal;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import java.util.Map;
import java.util.function.Supplier;

/** Peer discovery and ordinary asynchronous conversations, not a second execution engine. */
public final class AgentsTool extends PersonalTool {
    AgentsTool(
            Supplier<PersonalAgentService> services, PersonalAgentStore store, ObjectMapper json) {
        super(
                "agents",
                "Find colleagues with suitable Runner capabilities and delegate goals through conversations. mainbot can create a colleague when needed. A reply or acknowledgement is not proof of task completion.",
                services,
                store,
                json);
        action(
                "discover",
                "Find Agents and registered Runners in this Harness by purpose, environment or capability. Returns your currentRunnerId for reuse. Private memory and conversations are not exposed.",
                fields("query", text("Optional search phrase.")),
                new String[] {});
        action(
                "create",
                "Create a persistent Agent in this Harness for a separate role, memory or project ownership. Omit runnerId to share your current Runner; pass a registered Runner ID for another machine. Multiple Agents can share a Runner with separately allocated project directories. This does not start a Runner, install software or log into an SSH host. Models are selected per run, not bound to Agents.",
                fields(
                        "name",
                        text("Name."),
                        "description",
                        text("Purpose and expertise."),
                        "instructions",
                        text("Role instructions."),
                        "runnerId",
                        text(
                                "Optional existing Runner ID from discover. Defaults to your bound Runner. Never put a hostname or SSH address here.")),
                new String[] {},
                "name",
                "description");
        action(
                "send",
                "Send a goal or follow-up asynchronously. Omit sessionId for a new independent delegation; reuse the returned sessionId for follow-ups. Replies wake this conversation automatically; finish this turn instead of polling. Attach taskId to track the goal.",
                fields(
                        "agentId",
                        text("Recipient Agent."),
                        "sessionId",
                        text("Existing delegation conversation."),
                        "text",
                        text("Goal, necessary context, constraints and acceptance checks."),
                        "taskId",
                        text("Optional owned task."),
                        "attachmentIds",
                        array(text("Image reference from this conversation.")),
                        "requiresVision",
                        json.createObjectNode()
                                .put("type", "boolean")
                                .put(
                                        "description",
                                        "Select an image-capable model for visual inspection, including images to be read on the Runner. Defaults to true when forwarding images; set false for delivery only."),
                        "modelId",
                        text(
                                "Pass the exact model requested by the user. Otherwise omit: visual work uses an enabled image model; other work uses the default.")),
                new String[] {},
                "agentId",
                "text");
        action(
                "inspect",
                "Read replies and execution state for a delegation you initiated. Does not expose unrelated conversations or private reasoning.",
                fields(
                        "agentId",
                        text("Recipient Agent."),
                        "sessionId",
                        text("Delegation conversation.")),
                new String[] {},
                "agentId",
                "sessionId");
    }

    @Override
    boolean supportsAction(Profile profile, String action) {
        return !"create".equals(action) || PersonalAgentService.MAINBOT_ID.equals(profile.id);
    }

    Object run(String id, ToolContext context, String action, JsonNode args) {
        PersonalAgentService service = services.get();
        switch (action) {
            case "discover":
                Map<String, Object> discovery =
                        service.discoverAgents(args.path("query").asText(""));
                discovery.put("currentRunnerId", service.require(id).runnerId);
                return discovery;
            case "create":
                Profile p = new Profile();
                p.name = required(args, "name");
                p.description = required(args, "description");
                p.instructions = args.path("instructions").asText("");
                p.runnerId = args.path("runnerId").asText(service.require(id).runnerId);
                return service.agentDescription(service.save(p));
            case "send":
                return service.sendToAgent(
                        id,
                        context.getSessionId(),
                        required(args, "agentId"),
                        args.path("sessionId").asText(null),
                        required(args, "text"),
                        args.path("taskId").asText(null),
                        context.getAttribute("personalReplyTo", ReplyTarget.class),
                        args.path("modelId").asText(null),
                        io.github.differentialmanifold.jagentharness.core.message.ConversationImages
                                .readIds(args.get("attachmentIds")),
                        args.path("requiresVision")
                                .asBoolean(args.path("attachmentIds").size() > 0));
            case "inspect":
                return service.inspectDelegation(
                        id, required(args, "agentId"), required(args, "sessionId"));
            default:
                throw new IllegalArgumentException("Unknown Agent action");
        }
    }
}

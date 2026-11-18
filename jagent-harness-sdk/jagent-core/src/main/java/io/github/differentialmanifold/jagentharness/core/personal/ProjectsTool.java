package io.github.differentialmanifold.jagentharness.core.personal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.message.ConversationImages;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.project.CreateSessionRequest;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import java.util.List;
import java.util.function.Supplier;

public final class ProjectsTool extends PersonalTool {
    ProjectsTool(Supplier<PersonalAgentService> s, PersonalAgentStore store, ObjectMapper json) {
        super(
                "projects",
                "Create, inspect and advance your own projects on your configured Runner.",
                s,
                store,
                json);
        action(
                "list",
                "List owned projects with their workspace and sessions. Match the requested directory and goal before submitting work.",
                fields(),
                new String[] {"projects_list"});
        action(
                "open",
                "Open the project for the requested directory or owned projectId; reuse it when found, otherwise create one. A different independent directory needs its own project. Omit both for a new private project. Runner is automatic. Set newSession for an independent conversation in an existing project.",
                fields(
                        "projectName",
                        text("New project name."),
                        "title",
                        text("Session title."),
                        "projectId",
                        text("Existing owned project; omit for a new project."),
                        "workspacePath",
                        text(
                                "Explicit target directory on your Runner; must match projectId when both are supplied."),
                        "newSession",
                        io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas
                                .booleanProperty(
                                        json, "Create another session instead of reusing one.")),
                new String[] {"projects_create"});
        action(
                "inspect",
                "Read execution state and the latest 40 messages, including stored image references for attach_images. Use beforeMessageId to paginate.",
                fields(
                        "sessionId",
                        text("Owned project session."),
                        "beforeMessageId",
                        text("Earlier-history cursor.")),
                new String[] {"projects_inspect"},
                "sessionId");
        action(
                "attach_images",
                "Attach selected images from an owned Project session to your next reply in this chat. Use when the user wants the actual image. Works on stored tool results even if the Project only replied with a written description or the Runner is offline. No model or OCR required.",
                fields(
                        "sessionId",
                        text("Owned Project session containing the original images."),
                        "attachmentIds",
                        array(
                                text(
                                        "Image reference from that Project (messageId:one-based index); get it from the Project report or inspect."))),
                new String[] {},
                "sessionId",
                "attachmentIds");
        action(
                "send",
                "Submit work asynchronously. Attach taskId for tracked work. Dependencies must be DONE. Same-workspace operations are exclusive unless both runs declare readOnly. A result wakes this conversation automatically; finish the turn instead of polling.",
                fields(
                        "sessionId",
                        text("Owned project session."),
                        "text",
                        text("Goal, constraints, methods and completion checks."),
                        "taskId",
                        text("Optional owned task ID; acceptance criteria must be set."),
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
                                "Pass the exact model requested by the user. Otherwise omit: visual work uses an enabled image model; other work uses the default."),
                        "readOnly",
                        io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas
                                .booleanProperty(
                                        json,
                                        "Declare non-mutating investigation; this is a scheduling contract, not an OS sandbox. Default false.")),
                new String[] {"projects_send"},
                "sessionId",
                "text");
        action(
                "steer",
                "Add a correction to a specific running project. Applied at a safe model/tool boundary; does not undo completed operations.",
                fields(
                        "sessionId",
                        text("Owned project session."),
                        "runId",
                        text("Active run ID from inspect or send."),
                        "text",
                        text("Updated direction or constraint.")),
                new String[] {},
                "sessionId",
                "runId",
                "text");
        action(
                "stop",
                "Request cancellation of a specific run. Inspect until it ends before replacing work; external processes may need separate verification.",
                fields(
                        "sessionId",
                        text("Owned project session."),
                        "runId",
                        text("Active run ID.")),
                new String[] {},
                "sessionId",
                "runId");
    }

    Object run(String id, ToolContext context, String action, JsonNode args) throws Exception {
        PersonalAgentService s = services.get();
        switch (action) {
            case "list":
                return s.projectCatalog(id);
            case "open":
                ObjectNode body = ((ObjectNode) args).deepCopy();
                body.remove("action");
                boolean newSession = body.path("newSession").asBoolean(false);
                body.remove("newSession");
                return s.openProjectSession(
                        id, json.treeToValue(body, CreateSessionRequest.class), newSession);
            case "inspect":
                return s.inspectProject(
                        id, required(args, "sessionId"), args.path("beforeMessageId").asText(null));
            case "attach_images":
                String session = required(args, "sessionId");
                s.requireProject(id, session);
                List<MessageImage> images =
                        s.attachments(
                                session, ConversationImages.readIds(args.get("attachmentIds")));
                ConversationImages.ReplySelection selection =
                        context.getAttribute(
                                ConversationImages.ReplySelection.ATTRIBUTE,
                                ConversationImages.ReplySelection.class);
                if (selection == null)
                    throw new IllegalStateException(
                            "Images must be attached during an Agent conversation");
                selection.select(images);
                return json.createObjectNode()
                        .put("attached", images.size())
                        .put(
                                "note",
                                "Selected for your next reply in this chat. Reply normally with the caption or the Project's description. Do not copy IDs, paths or Base64 into the reply.");
            case "steer":
                return s.steerProject(
                        id,
                        required(args, "sessionId"),
                        required(args, "runId"),
                        required(args, "text"));
            case "stop":
                return s.stopProject(id, required(args, "sessionId"), required(args, "runId"));
            default:
                Inbox watch =
                        s.submitProject(
                                id,
                                context.getSessionId(),
                                required(args, "sessionId"),
                                required(args, "text"),
                                context.getAttribute("personalReplyTo", ReplyTarget.class),
                                args.path("taskId").asText(null),
                                args.path("readOnly").asBoolean(false),
                                args.path("modelId").asText(null),
                                io.github.differentialmanifold.jagentharness.core.message
                                        .ConversationImages.readIds(args.get("attachmentIds")),
                                args.path("requiresVision")
                                        .asBoolean(args.path("attachmentIds").size() > 0));
                return json.createObjectNode()
                        .put("projectSessionId", watch.projectSessionId)
                        .put("projectRunId", watch.projectRunId)
                        .put("id", watch.id)
                        .put("status", "ACCEPTED");
        }
    }
}

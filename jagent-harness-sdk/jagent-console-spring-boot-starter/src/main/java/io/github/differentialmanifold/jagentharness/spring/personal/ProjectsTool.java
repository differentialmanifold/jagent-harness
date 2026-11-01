package io.github.differentialmanifold.jagentharness.spring.personal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.spring.web.dto.CreateSessionRequest;
import org.springframework.beans.factory.ObjectProvider;

public final class ProjectsTool extends PersonalTool {
    ProjectsTool(
            ObjectProvider<PersonalAgentService> s, PersonalAgentStore store, ObjectMapper json) {
        super(
                "projects",
                "Create, inspect and advance your own projects on your configured Runner.",
                s,
                store,
                json);
        action(
                "list",
                "List owned project sessions; group by projectId.",
                fields(),
                new String[] {"projects_list"});
        action(
                "create",
                "Create a project and first session, or add a session to an owned project. Runner is automatic; omit workspacePath for a new private directory.",
                fields(
                        "projectName",
                        text("New project name."),
                        "title",
                        text("Session title."),
                        "projectId",
                        text("Existing owned project; omit for a new project."),
                        "workspacePath",
                        text("Optional explicit directory on your Runner.")),
                new String[] {"projects_create"});
        action(
                "inspect",
                "Read execution state and latest 40 messages; use beforeMessageId to paginate.",
                fields(
                        "sessionId",
                        text("Owned project session."),
                        "beforeMessageId",
                        text("Earlier-history cursor.")),
                new String[] {"projects_inspect"},
                "sessionId");
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
        PersonalAgentService s = services.getObject();
        switch (action) {
            case "list":
                return s.projectSessions(id);
            case "create":
                ObjectNode body = ((ObjectNode) args).deepCopy();
                body.remove("action");
                return s.createProjectSession(
                        id, json.treeToValue(body, CreateSessionRequest.class));
            case "inspect":
                return s.inspectProject(
                        id, required(args, "sessionId"), args.path("beforeMessageId").asText(null));
            case "steer":
                return s.steerProject(
                        id,
                        required(args, "sessionId"),
                        required(args, "runId"),
                        required(args, "text"));
            case "stop":
                return s.stopProject(id, required(args, "sessionId"), required(args, "runId"));
            default:
                return s.submitProject(
                        id,
                        context.getSessionId(),
                        required(args, "sessionId"),
                        required(args, "text"),
                        context.getAttribute("personalReplyTo", ReplyTarget.class),
                        args.path("taskId").asText(null),
                        args.path("readOnly").asBoolean(false));
        }
    }
}

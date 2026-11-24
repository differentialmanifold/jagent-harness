package io.github.differentialmanifold.jagentharness.core.personal;

import static io.github.differentialmanifold.jagentharness.core.personal.ManagementSchemas.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.project.*;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Console project/session administration; autonomous work remains in the projects tool. */
public final class ProjectManagement {
    private ProjectManagement() {}

    public static void register(
            HarnessOperations ops,
            Supplier<ProjectService> projects,
            Supplier<RunnerService> runners,
            Supplier<PersonalAgentService> agents,
            ObjectMapper json) {
        ops.register(
                "runners.list",
                "List this user's registered execution terminals and capabilities. Does not expose Runner secrets.",
                object(fields()),
                (ctx, a) -> projects.get().runners());
        ops.register(
                "runners.browse",
                "Browse directories on an existing Runner, as in Console's workspace selector. This does not read files or execute shell commands.",
                object(
                        fields(
                                "runnerId",
                                text("Existing Runner ID."),
                                "path",
                                text("Optional directory to browse.")),
                        "runnerId"),
                (ctx, a) ->
                        runners.get()
                                .browse(a.get("runnerId").asText(), a.path("path").asText(null))
                                .get(30, TimeUnit.SECONDS));
        ops.register(
                "projects.list",
                "List project sessions. Omit ownerAgentId for the user's own projects; supply an Agent ID for its projects. Sessions with the same projectId belong to one project.",
                object(fields("ownerAgentId", text("Optional owning Agent ID."))),
                (ctx, a) -> {
                    String owner = a.path("ownerAgentId").asText(null);
                    if (owner != null) agents.get().require(owner);
                    return projects.get().sessions(owner);
                });
        ObjectNode sessionFields = fields("sessionId", text("Existing project session ID."));
        ObjectNode inspectFields = sessionFields.deepCopy();
        inspectFields.set(
                "before", text("Optional beforeMessageId cursor to read earlier messages."));
        ops.register(
                "projects.inspect",
                "Read project settings and a page of bounded message excerpts. Pass the returned beforeMessageId as before for earlier messages. This does not transfer project ownership to mainbot.",
                object(inspectFields, "sessionId"),
                (ctx, a) -> {
                    requireProject(projects.get(), a.get("sessionId").asText());
                    return projects.get()
                            .inspect(a.get("sessionId").asText(), a.path("before").asText(null));
                });
        ObjectNode createFields =
                fields(
                        "title",
                        text("Conversation title."),
                        "projectName",
                        text("Name for a new project."),
                        "projectId",
                        text("Existing project ID to create another session in it."),
                        "runnerId",
                        text(
                                "Existing Runner ID for a new project. Omit only for a server-tools-only project."),
                        "workspacePath",
                        text("Optional workspace on that Runner. Omit to allocate one."),
                        "ownerAgentId",
                        text("Optional owner Agent. Omit for the user's project list."));
        ops.register(
                "projects.createSession",
                "Create a project or a session in an existing project on the user's behalf. For your own autonomous work, use projects open instead.",
                object(createFields, "title"),
                (ctx, a) -> {
                    ObjectNode request = (ObjectNode) a.deepCopy();
                    String owner = request.path("ownerAgentId").asText(null);
                    request.remove("ownerAgentId");
                    CreateSessionRequest create =
                            json.treeToValue(request, CreateSessionRequest.class);
                    return owner == null
                            ? projects.get().createSession(null, create)
                            : agents.get().createProjectSession(owner, create);
                });
        ObjectNode renameFields = sessionFields.deepCopy();
        renameFields.set("title", text("New session title."));
        ops.register(
                "projects.renameSession",
                "Rename a project conversation.",
                object(renameFields, "sessionId", "title"),
                (ctx, a) -> {
                    requireProject(projects.get(), a.get("sessionId").asText());
                    return projects.get()
                            .renameSession(a.get("sessionId").asText(), a.get("title").asText());
                });
        ops.register(
                "projects.deleteSession",
                "Delete a project conversation only when requested by the user. This does not delete Runner workspace files.",
                object(sessionFields, "sessionId"),
                (ctx, a) -> {
                    requireProject(projects.get(), a.get("sessionId").asText());
                    projects.get().deleteSession(a.get("sessionId").asText());
                    return Collections.singletonMap("deleted", a.get("sessionId").asText());
                });
        ops.register(
                "projects.tools",
                "Read the Runner tools enabled for this project. runners.list includes the Runner's available tool catalog.",
                object(sessionFields, "sessionId"),
                (ctx, a) ->
                        runners.get()
                                .project(
                                        requireProject(projects.get(), a.get("sessionId").asText())
                                                .getProjectId()));
        ObjectNode toolsFields = sessionFields.deepCopy();
        toolsFields.set(
                "enabledTools",
                strings(
                        "Complete selected Runner tool names. Preserve other enabled tools unless explicitly changing them."));
        ops.register(
                "projects.selectTools",
                "Update the project's Runner tool selection using the same validation as Console. Does not change shared server tools.",
                object(toolsFields, "sessionId", "enabledTools"),
                (ctx, a) ->
                        runners.get()
                                .selectTools(
                                        requireProject(projects.get(), a.get("sessionId").asText())
                                                .getProjectId(),
                                        Arrays.asList(
                                                json.treeToValue(
                                                        a.get("enabledTools"), String[].class))));
    }

    private static SessionRecord requireProject(ProjectService projects, String session) {
        SessionRecord record = projects.session(session);
        if (record.isStandalone())
            throw new IllegalArgumentException("Use agents operations for personal conversations");
        return record;
    }
}

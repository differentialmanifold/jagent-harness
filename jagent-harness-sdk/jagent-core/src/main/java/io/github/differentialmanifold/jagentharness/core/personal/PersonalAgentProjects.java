package io.github.differentialmanifold.jagentharness.core.personal;

import static io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentSupport.*;

import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.execution.*;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.project.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.*;
import java.util.concurrent.*;

/** Coordinates owned projects and task evidence using shared project and execution services. */
final class PersonalAgentProjects {
    private final PersonalAgentService agents;
    private final PersonalAgentStore store;
    private final PersonalTaskPlan plans;
    private final ProjectService projects;
    private final RunService runs;
    private volatile boolean closed;

    PersonalAgentProjects(
            PersonalAgentService agents,
            PersonalAgentStore store,
            PersonalTaskPlan plans,
            ProjectService projects,
            RunService runs) {
        this.agents = agents;
        this.store = store;
        this.plans = plans;
        this.projects = projects;
        this.runs = runs;
    }

    private Profile require(String agent) {
        return agents.require(agent);
    }

    private void conversation(String agent, String session) {
        agents.conversation(agent, session);
    }

    public List<SessionRecord> list(String agent) {
        require(agent);
        return projects.sessions(agent);
    }

    public List<RunnerInfo> runners() {
        return projects.runners();
    }

    public SessionRecord create(String agent, CreateSessionRequest request) {
        Profile p = require(agent);
        if (request.getProjectId() != null && !request.getProjectId().isEmpty()) {
            SessionRecord existing =
                    list(agent).stream()
                            .filter(s -> request.getProjectId().equals(s.getProjectId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Project not found"));
            request.setProjectName(existing.getProjectName());
            request.setWorkspacePath(existing.getWorkspacePath());
        } else {
            request.setRunnerId(p.runnerId);
        }
        return projects.createSession(agent, request);
    }

    public SessionRecord requireProject(String agent, String session) {
        require(agent);
        SessionRecord record;
        try {
            record = projects.session(session);
        } catch (IllegalArgumentException missing) {
            record = null;
        }
        if (record == null || record.isStandalone() || !agent.equals(record.getOwnerAgentId()))
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.NOT_FOUND,
                    "PROJECT_NOT_FOUND",
                    "Project not found for this Agent");
        return record;
    }

    public Map<String, Object> inspect(String agent, String session, String before) {
        requireProject(agent, session);
        Map<String, Object> out = projects.inspect(session, before);
        try {
            out.put("state", runs.state(session));
        } catch (ProtocolException missing) {
            if (missing.status != 404) throw missing;
        }
        return out;
    }

    Inbox submit(
            String agent,
            String conversation,
            String session,
            String text,
            ReplyTarget replyTo,
            String taskId,
            boolean readOnly) {
        conversation(agent, conversation);
        SessionRecord project = requireProject(agent, session);
        Card task = taskId == null ? null : plans.require(agent, taskId);
        if (task != null) {
            if (PersonalTaskPlan.terminal(task))
                throw new IllegalArgumentException("Reopen this task before submitting more work");
            plans.ready(task);
            if (task.acceptance.trim().isEmpty())
                throw new IllegalArgumentException("Set task acceptance criteria before dispatch");
            if (task.conversationId != null && !conversation.equals(task.conversationId))
                throw new IllegalArgumentException("Continue this task in its owning conversation");
        }
        String scope =
                String.valueOf(projects.runnerId(session))
                        + ":"
                        + (project.getWorkspacePath() == null
                                ? project.getProjectId()
                                : project.getWorkspacePath()
                                        .replace('\\', '/')
                                        .replaceAll("/+$", ""));
        for (Inbox running : store.inbox("WAITING")) {
            if (taskId != null && taskId.equals(running.taskId))
                throw new IllegalArgumentException("Task already has an outstanding run");
            if (scope.equals(running.executionScope) && (!readOnly || !running.readOnly))
                throw new IllegalArgumentException(
                        "Workspace is in use. Wait for the write to finish, or use an independent workspace");
        }
        Inbox watch = new Inbox();
        watch.id = Ids.newId("watch");
        watch.agentId = agent;
        watch.sessionId = conversation;
        watch.projectSessionId = session;
        watch.projectRunId = Ids.newId("run");
        watch.source = "PROJECT";
        watch.taskId = taskId;
        watch.executionScope = scope;
        watch.readOnly = readOnly;
        watch.taskRequirements = task == null ? null : plans.requirements(task);
        watch.replyTo = replyTo;
        watch.status = "WAITING";
        watch.text = text(text, "text", 32000);
        watch.createdAt = System.currentTimeMillis();
        if (task == null) store.enqueue(watch);
        else {
            task.sessionId = session;
            task.projectId = project.getProjectId();
            task.conversationId = conversation;
            task.lastRunId = watch.projectRunId;
            task.status = "DOING";
            if (!store.submitTask(task, watch)) throw conflict();
        }
        dispatch(watch);
        return watch;
    }

    private void dispatch(Inbox watch) {
        if (closed) return;
        watch.dispatchStarted = true;
        if (!store.update(watch, "WAITING")) return;
        String input =
                "[From personal Agent: "
                        + require(watch.agentId).name
                        + "]\n\n"
                        + watch.text
                        + (watch.taskRequirements == null
                                ? ""
                                : "\nTask requirements at dispatch:\n" + watch.taskRequirements)
                        + (watch.readOnly
                                ? "\nRead-only investigation: do not modify files or other environment state."
                                : "");
        try {
            ChatSubmission request = new ChatSubmission();
            request.sessionId = watch.projectSessionId;
            request.runId = watch.projectRunId;
            request.messages = Collections.singletonList(ChatMessage.user(input));
            runs.submit(request, null)
                    .result()
                    .whenComplete(
                            (result, error) ->
                                    finishWatch(
                                            watch,
                                            error == null
                                                    ? resultText(result)
                                                    : "Project execution interrupted: "
                                                            + error.getMessage()));
        } catch (RuntimeException e) {
            finishWatch(watch, "Could not submit project work: " + e.getMessage());
        }
    }

    private void finishWatch(Inbox watch, String result) {
        synchronized (agents) {
            watch.text =
                    "Project result for session "
                            + watch.projectSessionId
                            + ", run "
                            + watch.projectRunId
                            + ".\n"
                            + bounded(result, 24000)
                            + (watch.taskId == null
                                    ? ""
                                    : "\nTask: "
                                            + watch.taskId
                                            + "\nRequirements at dispatch: "
                                            + watch.taskRequirements
                                            + "\nRequirements still current: "
                                            + (store.card(watch.taskId) != null
                                                    && Objects.equals(
                                                            watch.taskRequirements,
                                                            plans.requirements(
                                                                    store.card(watch.taskId)))))
                            + "\nA completed run is not task acceptance. Verify against current criteria and record project message evidence. Assess the goal against this evidence. Inspect the project history if needed; update tracking and continue or report the outcome.";
            watch.status = "PENDING";
            store.update(watch, "WAITING");
        }
    }

    Object steer(String agent, String session, String run, String text) {
        requireProject(agent, session);
        runs.requireRun(session, run);
        return runs.input(
                run, text(text, "text", 32000), Ids.newId("input"), Collections.emptyList());
    }

    Map<String, Object> stop(String agent, String session, String run) {
        requireProject(agent, session);
        runs.requireRun(session, run);
        runs.stop(run);
        return Collections.singletonMap("stopRequested", true);
    }

    void checkResults() {
        for (Inbox watch : store.inbox("WAITING")) {
            try {
                requireProject(watch.agentId, watch.projectSessionId);
            } catch (PersonalAgentException e) {
                watch.status = "FAILED";
                watch.error =
                        "This project is no longer available to this Agent. Review its own tasks before continuing.";
                store.update(watch, "WAITING");
                continue;
            }
            ChatResponse execution = runs.execution(watch.projectSessionId, watch.projectRunId);
            if (execution != null
                    && !Arrays.asList("RUNNING", "REQUIRES_ACTION").contains(execution.status))
                finishWatch(watch, resultText(execution));
            else if (execution == null) {
                if (!watch.dispatchStarted) dispatch(watch);
                else
                    finishWatch(
                            watch,
                            "Submission outcome is unknown. Inspect history and the environment before deciding whether to retry.");
            }
        }
    }

    private String resultText(ChatResponse result) {
        return result.status + "\n" + (result.answer == null ? "" : result.answer);
    }

    void close() {
        closed = true;
    }
}

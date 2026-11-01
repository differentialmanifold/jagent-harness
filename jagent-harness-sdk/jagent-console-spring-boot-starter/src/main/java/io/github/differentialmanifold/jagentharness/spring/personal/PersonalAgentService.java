package io.github.differentialmanifold.jagentharness.spring.personal;

import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.protocol.*;
import io.github.differentialmanifold.jagentharness.spring.web.*;
import io.github.differentialmanifold.jagentharness.spring.web.dto.CreateSessionRequest;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;

/** A small, single-server inbox worker. Each Agent operates its own ordinary projects. */
public class PersonalAgentService {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(PersonalAgentService.class);
    final PersonalAgentStore store;
    private final SessionRepository records;
    private final SessionManager sessions;
    private final SessionStore history;
    private final AgentHarness harness;
    private final ChatService chat;
    private final RunnerService runners;
    private final PersonalAgentKnowledge knowledge;
    private final PersonalAgentLearning learning;
    private final PersonalTaskPlan plans;
    @org.springframework.beans.factory.annotation.Autowired private RunInputCoordinator inputs;
    private final ScheduledExecutorService clock =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "personal-inbox"));
    private final ExecutorService workers =
            Executors.newCachedThreadPool(r -> daemon(r, "personal-agent"));
    private final ExecutorService projects =
            Executors.newCachedThreadPool(r -> daemon(r, "personal-project"));
    private final ConcurrentMap<String, TurnStop> active = new ConcurrentHashMap<>();
    private final Set<String> localProjects = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile boolean failed;

    public PersonalAgentService(
            PersonalAgentStore store,
            SessionRepository records,
            SessionManager sessions,
            SessionStore history,
            AgentHarness harness,
            ChatService chat,
            RunnerService runners,
            PersonalAgentKnowledge knowledge,
            PersonalAgentLearning learning) {
        this.store = store;
        this.records = records;
        this.sessions = sessions;
        this.history = history;
        this.harness = harness;
        this.chat = chat;
        this.runners = runners;
        this.knowledge = knowledge;
        this.learning = learning;
        this.plans = new PersonalTaskPlan(store, records, history);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public void start() {
        restoreProjectOwnership();
        recoverInterrupted();
        clock.scheduleWithFixedDelay(this::tick, 500, 1000, TimeUnit.MILLISECONDS);
    }

    synchronized void recoverInterrupted() {
        for (Inbox m : store.inbox("RUNNING")) {
            if (active.containsKey(m.agentId)) continue;
            if (!"LEARNING".equals(m.source)) resolveUncertainTools(m.sessionId);
            m.status = "FAILED";
            m.error =
                    "Server restarted during this turn. A recovery check will inspect the existing work before continuing.";
            if (!"LEARNING".equals(m.source)) {
                Inbox recovery = new Inbox();
                recovery.id = "recover_" + RunnerService.hash(m.id).substring(0, 40);
                recovery.agentId = m.agentId;
                recovery.sessionId = m.sessionId;
                recovery.source = "RECOVERY";
                recovery.replyTo = m.replyTo;
                recovery.taskId = m.taskId;
                recovery.createdAt = System.currentTimeMillis();
                recovery.text =
                        "A previous decision turn was interrupted. Inspect current tasks, project run states and history before acting. "
                                + "Do not repeat uncertain operations. Original request/event: "
                                + bounded(m.text, 8000);
                store.recoverTurn(m, recovery);
            } else store.update(m, "RUNNING");
        }
    }

    void restoreProjectOwnership() {
        com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper();
        for (Profile p : store.profiles())
            for (Conversation c : store.conversations(p.id)) {
                Map<String, Boolean> creations = new HashMap<>();
                for (AgentMessage m : history.findMessages(c.sessionId)) {
                    if (m.getToolCalls() != null)
                        for (io.github.differentialmanifold.jagentharness.core.tool.ToolCall call :
                                m.getToolCalls()) {
                            try {
                                com.fasterxml.jackson.databind.JsonNode args =
                                        json.readTree(call.getArgumentsJson());
                                if (("console".equals(call.getName())
                                                && "projects.create"
                                                        .equals(args.path("action").asText())
                                                && args.path("body")
                                                        .path("projectId")
                                                        .asText("")
                                                        .isEmpty())
                                        || (("projects_create".equals(call.getName())
                                                        || ("projects".equals(call.getName())
                                                                && "create"
                                                                        .equals(
                                                                                args.path("action")
                                                                                        .asText())))
                                                && args.path("projectId").asText("").isEmpty()))
                                    creations.put(call.getToolCallId(), true);
                            } catch (Exception ignored) {
                                /* Old invalid calls did not create a project. */
                            }
                        }
                    if ("tool".equals(m.getRole()) && creations.containsKey(m.getToolCallId())) {
                        try {
                            String project =
                                    json.readTree(m.getContent()).path("projectId").asText(null);
                            if (project != null) records.assignProjectOwnerIfUnowned(project, p.id);
                        } catch (Exception e) {
                            LOG.warn(
                                    "Could not restore project ownership from {}",
                                    m.getMessageId(),
                                    e);
                        }
                    }
                }
            }
    }

    private void resolveUncertainTools(String session) {
        List<AgentMessage> messages = history.findMessages(session);
        for (io.github.differentialmanifold.jagentharness.core.tool.ToolCall call :
                ConversationHistory.pendingTools(messages).values()) {
            AgentMessage calling =
                    ConversationHistory.callingMessage(messages, call.getToolCallId());
            AgentMessage result =
                    AgentMessage.tool(
                            session,
                            call.getToolCallId(),
                            call.getName(),
                            "Execution outcome is UNKNOWN after interruption. Inspect the affected project, tracking record, or message before deciding whether any action is still necessary. Do not replay automatically.");
            result.setRunId(calling.getRunId());
            result.setTurnId(calling.getTurnId());
            history.appendMessage(result);
        }
    }

    public Profile require(String id) {
        Profile p = store.profile(id);
        if (p == null) throw new ProtocolException(404, "AGENT_NOT_FOUND", "Agent not found");
        return p;
    }

    public Profile save(Profile p) {
        p.name = text(p.name, "name", 128);
        p.disabledTools =
                p.disabledTools == null
                        ? new ArrayList<>()
                        : new ArrayList<>(new LinkedHashSet<>(p.disabledTools));
        if (p.disabledTools.size() > 100
                || p.disabledTools.stream()
                        .anyMatch(n -> n == null || !n.matches("[a-z][a-z0-9_]{0,127}")))
            throw new IllegalArgumentException("Invalid disabled tool names");
        p.runnerId = p.runnerId == null || p.runnerId.trim().isEmpty() ? null : p.runnerId.trim();
        p.workspacePath =
                p.workspacePath == null || p.workspacePath.trim().isEmpty()
                        ? null
                        : text(p.workspacePath, "workspacePath", 2048);
        if (p.runnerId != null) {
            RunnerInfo runner =
                    runners().stream()
                            .filter(r -> p.runnerId.equals(r.runnerId))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Choose an existing Runner"));
        }
        // Project directories are allocated by the selected Runner, per owner and project.
        p.workspacePath = null;
        p.instructions = PersonalAgentKnowledge.limited(p.instructions, 16000, "Instructions");
        p.memory =
                PersonalAgentKnowledge.limited(
                        p.memory, PersonalAgentKnowledge.MEMORY_LIMIT, "Memory");
        if (p.id == null) {
            p.id = Ids.newId("agent");
            p.revision = 0;
        } else require(p.id);
        knowledge.saveProfile(p);
        return p;
    }

    public Conversation conversation(String agent, String session) {
        require(agent);
        Conversation c = store.conversation(session);
        if (c == null || !agent.equals(c.agentId))
            throw new ProtocolException(
                    404, "CONVERSATION_NOT_FOUND", "Conversation not found for this Agent");
        return c;
    }

    public Conversation newConversation(String agent, String title) {
        require(agent);
        title = text(title == null ? "New conversation" : title, "title", 256);
        SessionRecord session = records.createStandalone(title);
        Conversation c = new Conversation();
        c.agentId = agent;
        c.sessionId = session.getSessionId();
        c.title = title;
        c.createdAt = System.currentTimeMillis();
        store.add(c);
        return c;
    }

    public synchronized Conversation renameConversation(
            String agent, String session, String title) {
        Conversation c = conversation(agent, session);
        c.title = text(title, "title", 256);
        store.renameConversation(c, () -> sessions.renameSession(session, c.title));
        return c;
    }

    public synchronized void deleteConversation(String agent, String session) {
        Conversation c = conversation(agent, session);
        if (active.containsKey(agent)
                || store.messages(session).stream().anyMatch(m -> "RUNNING".equals(m.status)))
            throw new ProtocolException(
                    409,
                    "AGENT_BUSY",
                    "Wait for this Agent's current turn to finish before deleting a chat.");
        store.deleteConversation(c, () -> sessions.deleteSession(session));
    }

    public synchronized Inbox send(String agent, String session, String content, String id) {
        if (!require(agent).enabled)
            throw new ProtocolException(
                    409, "AGENT_PAUSED", "Resume this Agent before sending messages");
        conversation(agent, session);
        Inbox m = new Inbox();
        m.id = id == null ? Ids.newId("msg") : text(id, "messageId", 128);
        m.agentId = agent;
        m.sessionId = session;
        m.source = "USER";
        m.text = text(content, "text", 32000);
        m.createdAt = System.currentTimeMillis();
        if (!store.enqueue(m)) {
            Inbox previous = store.message(m.id);
            if (!agent.equals(previous.agentId)
                    || !session.equals(previous.sessionId)
                    || !m.text.equals(previous.text)) throw conflict();
            return previous;
        }
        return m;
    }

    public List<AgentMessage> history(String agent, String session) {
        conversation(agent, session);
        return history.findMessages(session);
    }

    public List<Card> cards(String agent) {
        require(agent);
        return store.cards().stream()
                .filter(c -> agent.equals(c.ownerId))
                .collect(java.util.stream.Collectors.toList());
    }

    public synchronized Card saveCard(Card c, String agent) {
        return saveCard(c, agent, null);
    }

    synchronized Card saveCard(Card c, String agent, ReplyTarget replyTo) {
        require(agent);
        Card old = c.id == null ? null : store.card(c.id);
        if (c.ownerId != null && !agent.equals(c.ownerId)
                || c.id != null && (old == null || !agent.equals(old.ownerId)))
            throw new ProtocolException(404, "TASK_NOT_FOUND", "Task not found for this Agent");
        // Follow-ups retain the task's originating conversation and delivery target.
        c.replyTo =
                old != null && Objects.equals(old.conversationId, c.conversationId)
                        ? old.replyTo
                        : replyTo;
        c.ownerId = agent;
        c.title = text(c.title, "title", 512);
        require(c.ownerId);
        c.progress = bounded(c.progress, 16000);
        c.lastRunId = old == null ? null : old.lastRunId;
        if (!Arrays.asList("TODO", "DOING", "WAITING", "DONE", "CANCELLED").contains(c.status))
            throw new IllegalArgumentException("Invalid card status");
        if (c.sessionId != null && !c.sessionId.isEmpty()) {
            SessionRecord session = requireProject(agent, c.sessionId);
            c.projectId = session.getProjectId();
        }
        if (c.conversationId != null && !c.conversationId.isEmpty())
            conversation(c.ownerId, c.conversationId);
        if (c.nextCheckAt > 0) {
            if (PersonalTaskPlan.terminal(c)) c.nextCheckAt = 0;
            else if (c.conversationId == null || c.conversationId.isEmpty())
                throw new IllegalArgumentException(
                        "Choose an owner conversation for the next check");
            else if (c.nextCheckAt < System.currentTimeMillis() + 60000)
                throw new IllegalArgumentException(
                        "Next check must be at least one minute from now");
        }
        if (c.id == null) {
            c.id = Ids.newId("card");
            c.revision = 0;
        } else if (store.card(c.id) == null)
            throw new ProtocolException(404, "CARD_NOT_FOUND", "Card not found");
        plans.validate(c, old);
        if (!store.save(c)) throw conflict();
        return c;
    }

    public List<Inbox> taskExecutions(String agent, String task) {
        return plans.executions(agent, task);
    }

    String taskContext(String agent, String conversation) {
        return plans.context(agent, conversation, null);
    }

    String runtimeContext(Inbox event) {
        return "[Application runtime context — data snapshot]\n"
                + "Current conversation: "
                + event.sessionId
                + "\nCurrent time (UTC): "
                + java.time.Instant.now()
                + "\nCurrent event source: "
                + event.source
                + "\nPersistent memory:\n"
                + require(event.agentId).memory
                + plans.context(event.agentId, event.sessionId, event.taskId);
    }

    public List<SessionRecord> projectSessions(String agent) {
        require(agent);
        List<SessionRecord> projects =
                sessions.listSessions().stream()
                        .filter(s -> agent.equals(s.getOwnerAgentId()))
                        .collect(java.util.stream.Collectors.toList());
        if (runners != null)
            for (SessionRecord session : projects)
                session.setRunnerId(runners.binding(session.getSessionId()));
        return projects;
    }

    public List<RunnerInfo> runners() {
        return runners == null ? Collections.emptyList() : runners.list();
    }

    public SessionRecord createProjectSession(String agent, CreateSessionRequest request) {
        Profile p = require(agent);
        if (request.getProjectId() != null && !request.getProjectId().isEmpty()) {
            SessionRecord existing =
                    projectSessions(agent).stream()
                            .filter(s -> request.getProjectId().equals(s.getProjectId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Project not found"));
            request.setProjectName(existing.getProjectName());
            request.setWorkspacePath(existing.getWorkspacePath());
        } else {
            request.setRunnerId(p.runnerId);
        }
        if (runners != null
                && (request.getRunnerId() != null && !request.getRunnerId().isEmpty()
                        || runners.project(request.getProjectId()) != null))
            return runners.createSession(request, runners.project(request.getProjectId()), agent);
        return sessions.createSession(
                request.getTitle(),
                request.getWorkspacePath(),
                request.getProjectName(),
                request.getProjectId(),
                agent);
    }

    public SessionRecord requireProject(String agent, String session) {
        require(agent);
        SessionRecord record = records.findBySessionId(session);
        if (record == null || record.isStandalone() || !agent.equals(record.getOwnerAgentId()))
            throw new ProtocolException(
                    404, "PROJECT_NOT_FOUND", "Project not found for this Agent");
        return record;
    }

    public Map<String, Object> inspectProject(String agent, String session, String before) {
        SessionRecord record = requireProject(agent, session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", record);
        List<AgentMessage> messages = history.findMessages(session);
        int end = messages.size();
        if (before != null && !before.isEmpty()) {
            end = -1;
            for (int i = 0; i < messages.size(); i++)
                if (before.equals(messages.get(i).getMessageId())) {
                    end = i;
                    break;
                }
            if (end < 0) throw new IllegalArgumentException("History cursor not found");
        }
        int start = Math.max(0, end - 40);
        out.put("messages", messages.subList(start, end));
        out.put("beforeMessageId", start == 0 ? null : messages.get(start).getMessageId());
        try {
            out.put("state", chat.state(session));
        } catch (ProtocolException e) {
            if (e.status != 404) throw e;
        }
        return out;
    }

    public synchronized Inbox submitProject(
            String agent, String conversation, String session, String text) {
        return submitProject(agent, conversation, session, text, null);
    }

    synchronized Inbox submitProject(
            String agent, String conversation, String session, String text, ReplyTarget replyTo) {
        return submitProject(agent, conversation, session, text, replyTo, null, false);
    }

    synchronized Inbox submitProject(
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
                (runners == null ? "server" : String.valueOf(runners.binding(session)))
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
        ChatRequest request = new ChatRequest();
        request.sessionId = watch.projectSessionId;
        request.runId = watch.projectRunId;
        request.messages =
                Collections.singletonList(
                        ChatMessage.user(
                                "[From personal Agent: "
                                        + require(watch.agentId).name
                                        + "]\n\n"
                                        + watch.text
                                        + (watch.taskRequirements == null
                                                ? ""
                                                : "\nTask requirements at dispatch:\n"
                                                        + watch.taskRequirements)
                                        + (watch.readOnly
                                                ? "\nRead-only investigation: do not modify files or other environment state."
                                                : "")));

        if (runners != null && runners.binding(request.sessionId) != null) {
            try {
                runners.job(request.runId);
                return;
            } catch (ProtocolException e) {
                if (e.status != 404) throw e;
            }
            try {
                runners.enqueue(request);
            } catch (RuntimeException e) {
                finishWatch(watch, "Could not submit project work: " + e.getMessage());
            }
        } else if (localProjects.add(request.runId)) {
            projects.submit(
                    () -> {
                        try {
                            ChatResponse result = chat.chat(request, null);
                            finishWatch(watch, result.status + "\n" + result.answer);
                        } catch (Exception e) {
                            finishWatch(watch, "Project execution interrupted: " + e.getMessage());
                        } finally {
                            localProjects.remove(request.runId);
                        }
                    });
        }
    }

    private synchronized void finishWatch(Inbox watch, String result) {
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

    public List<Conversation> conversations(String agent) {
        require(agent);
        return store.conversations(agent);
    }

    public synchronized Object steerProject(
            String agent, String session, String runId, String text) {
        requireProjectRun(agent, session, runId);
        return inputs.submitInput(runId, text(text, "text", 32000), Ids.newId("input"));
    }

    public synchronized Map<String, Object> stopProject(
            String agent, String session, String runId) {
        requireProjectRun(agent, session, runId);
        boolean remote = runners != null && runners.cancel(runId);
        try {
            chat.cancel(runId);
        } catch (ProtocolException e) {
            if (!remote || e.status != 404) throw e;
        }
        return Collections.singletonMap("stopRequested", true);
    }

    private void requireProjectRun(String agent, String session, String runId) {
        requireProject(agent, session);
        if (runners != null && runners.binding(session) != null) {
            RunnerJob job = runners.job(runId);
            if (!session.equals(job.request.sessionId))
                throw new IllegalArgumentException("Run belongs to another session");
            if (job.response != null) throw new IllegalArgumentException("Run has already ended");
        } else if (!runId.equals(chat.state(session).runId))
            throw new IllegalArgumentException("Run is no longer current");
    }

    // One nudge per task revision. Waiting/blocked tasks require an explicit check or new input.
    private void continueReadyTasks() {
        Set<String> occupied = new HashSet<>();
        for (String status : Arrays.asList("PENDING", "RUNNING"))
            for (Inbox m : store.inbox(status))
                if (!"LEARNING".equals(m.source)) occupied.add(m.agentId);
        List<Card> cards = store.cards();
        List<Inbox> outstanding = store.inbox("WAITING");
        for (Card c : cards) {
            if (occupied.contains(c.ownerId)
                    || active.containsKey(c.ownerId)
                    || c.conversationId == null
                    || c.acceptance.trim().isEmpty()
                    || c.nextCheckAt > 0
                    || !("TODO".equals(c.status) || "DOING".equals(c.status))
                    || !require(c.ownerId).enabled
                    || store.conversation(c.conversationId) == null) continue;
            if (outstanding.stream()
                    .anyMatch(
                            run ->
                                    c.id.equals(run.taskId)
                                            || (run.taskId == null
                                                    && c.conversationId.equals(run.sessionId))))
                continue;
            try {
                plans.ready(c);
            } catch (IllegalArgumentException blocked) {
                continue;
            }
            if (cards.stream()
                    .anyMatch(
                            child ->
                                    c.id.equals(child.parentId)
                                            && !PersonalTaskPlan.terminal(child))) continue;
            Inbox next = new Inbox();
            next.id = "continue_" + c.id + "_" + c.revision;
            next.agentId = c.ownerId;
            next.sessionId = c.conversationId;
            next.source = "CONTINUE";
            next.taskId = c.id;
            next.replyTo = c.replyTo;
            next.createdAt = System.currentTimeMillis();
            next.text =
                    "Task "
                            + c.id
                            + " has unfinished work and no outstanding execution or scheduled check. Inspect existing evidence, then advance the plan, verify results, or record a blocker as WAITING. Do not repeat a run merely because its outcome is uncertain.";
            if (store.enqueue(next)) occupied.add(c.ownerId);
        }
    }

    public synchronized void tick() {
        if (closed) return;
        try {
            for (Map.Entry<String, TurnStop> entry : active.entrySet())
                if (!require(entry.getKey()).enabled) entry.getValue().stop();
            recoverInterrupted();
            for (Inbox watch : store.inbox("WAITING")) {
                try {
                    requireProject(watch.agentId, watch.projectSessionId);
                } catch (ProtocolException e) {
                    watch.status = "FAILED";
                    watch.error =
                            "This project is no longer available to this Agent. Review its own tasks before continuing.";
                    store.update(watch, "WAITING");
                    continue;
                }
                if (runners != null && runners.binding(watch.projectSessionId) != null) {
                    try {
                        RunnerJob job = runners.job(watch.projectRunId);
                        if (job.response != null)
                            finishWatch(watch, job.response.status + "\n" + job.response.answer);
                    } catch (ProtocolException e) {
                        if (e.status != 404) throw e;
                        if (!watch.dispatchStarted) dispatch(watch);
                        else
                            finishWatch(
                                    watch,
                                    "Submission outcome is unknown. Inspect history and the environment before deciding whether to retry.");
                    }
                } else if (!localProjects.contains(watch.projectRunId)) {
                    if (!watch.dispatchStarted) {
                        dispatch(watch);
                        continue;
                    }
                    // An unobserved local run is never silently replayed after a server restart.
                    try {
                        ChatResponse state = chat.state(watch.projectSessionId);
                        finishWatch(
                                watch,
                                watch.projectRunId.equals(state.runId)
                                        ? state.status + "\n" + state.answer
                                        : "Server restarted; inspect project history before deciding what to run next.");
                    } catch (ProtocolException e) {
                        if (e.status != 404) throw e;
                        finishWatch(
                                watch,
                                "Server restarted before submission was confirmed. Inspect and resubmit if needed.");
                    }
                }
            }
            for (Card c : store.cards())
                if (c.nextCheckAt > 0
                        && c.nextCheckAt <= System.currentTimeMillis()
                        && !PersonalTaskPlan.terminal(c)
                        && require(c.ownerId).enabled) {
                    Inbox check = new Inbox();
                    check.id = "check_" + c.id + "_" + c.revision;
                    check.agentId = c.ownerId;
                    check.sessionId = c.conversationId;
                    check.source = "CHECK";
                    check.taskId = c.id;
                    check.replyTo = c.replyTo;
                    check.text =
                            "Scheduled review of tracking card "
                                    + c.id
                                    + ": "
                                    + c.title
                                    + "\n"
                                    + c.progress;
                    check.createdAt = System.currentTimeMillis();
                    store.scheduleCheck(c, check);
                }
            continueReadyTasks();
            List<Inbox> pending = new ArrayList<>(store.inbox("PENDING"));
            pending.removeIf(
                    m ->
                            !Arrays.asList(
                                            "USER",
                                            "FEISHU",
                                            "PROJECT",
                                            "CHECK",
                                            "RECOVERY",
                                            "CONTINUE",
                                            "LEARNING")
                                    .contains(m.source));
            pending.sort(Comparator.comparing(m -> "LEARNING".equals(m.source)));
            for (Inbox m : pending) {
                TurnStop running = active.get(m.agentId);
                if (!"LEARNING".equals(m.source) && running != null && running.review)
                    running.stop();
            }
            for (Inbox m : pending)
                if (require(m.agentId).enabled
                        && active.putIfAbsent(m.agentId, new TurnStop("LEARNING".equals(m.source)))
                                == null) {
                    m.status = "RUNNING";
                    try {
                        if (store.update(m, "PENDING")) workers.submit(() -> run(m));
                        else active.remove(m.agentId);
                    } catch (RuntimeException e) {
                        active.remove(m.agentId);
                        throw e;
                    }
                }
            failed = false;
        } catch (Exception e) {
            if (!failed)
                LOG.warn("Personal Agent inbox check failed; persisted work remains available", e);
            failed = true;
        }
    }

    private void run(Inbox m) {
        ScheduledFuture<?> deadline = null;
        try {
            if ("LEARNING".equals(m.source)) {
                TurnStop stop = active.get(m.agentId);
                deadline =
                        clock.schedule(stop::stop, learning.timeoutMillis, TimeUnit.MILLISECONDS);
                m.answer = learning.review(m, stop);
                m.status = "DONE";
                store.update(m, "RUNNING");
                return;
            }
            final long startingRevision = require(m.agentId).revision;
            final int[] calls = {0};
            final boolean[] checkpoint = {false};
            String input =
                    "USER".equals(m.source) || "FEISHU".equals(m.source)
                            ? m.text
                            : "[" + m.source + "]\n" + m.text;
            String runId = messageRunId(m);
            AgentRunResult result =
                    harness.run(
                            m.sessionId,
                            input,
                            AgentRunOptions.builder()
                                    .runId(runId)
                                    .runtimeContext(runtimeContext(m))
                                    .attribute("personalAgentId", m.agentId)
                                    .attribute("personalReplyTo", m.replyTo)
                                    .attribute("personalEventSource", m.source)
                                    .finalAnswerMapper(
                                            raw ->
                                                    PersonalAgentReply.decode(
                                                            raw,
                                                            !"USER".equals(m.source)
                                                                    && !"FEISHU".equals(m.source)))
                                    .eventConsumer(
                                            event -> {
                                                if (io.github.differentialmanifold.jagentharness
                                                        .core.event.AgentEvent.TOOL_EXECUTION_END
                                                        .equals(event.getType())) calls[0]++;
                                                if (!checkpoint[0]
                                                        && io.github.differentialmanifold
                                                                .jagentharness.core.event.AgentEvent
                                                                .COMPACTION_START
                                                                .equals(event.getType())) {
                                                    checkpoint[0] = true;
                                                    try {
                                                        learning.checkpoint(m, event);
                                                    } catch (Exception e) {
                                                        LOG.warn(
                                                                "Could not capture learning checkpoint for {}",
                                                                m.id,
                                                                e);
                                                    }
                                                }
                                            })
                                    .stopSignal(active.get(m.agentId))
                                    .build());
            m.status = "COMPLETED".equals(result.getStatus()) ? "DONE" : "FAILED";
            m.answer = result.getAnswer() == null ? "" : result.getAnswer();
            m.delivered = m.replyTo == null || ("DONE".equals(m.status) && m.answer.isEmpty());
            if (!"DONE".equals(m.status)) {
                m.error =
                        "Turn ended with "
                                + result.getStatus()
                                + ". Inspect its history before continuing.";
                resolveUncertainTools(m.sessionId);
            }
            store.update(m, "RUNNING");
            if ("DONE".equals(m.status)
                    && !checkpoint[0]
                    && ("PROJECT".equals(m.source) || calls[0] >= 4)
                    && require(m.agentId).revision == startingRevision) {
                try {
                    learning.enqueue(
                            m.agentId, m.sessionId, "AFTER_WORK", m.id, m.projectSessionId);
                } catch (RuntimeException e) {
                    LOG.warn("Could not queue learning review for {}", m.id, e);
                }
            }
        } catch (Exception e) {
            m.status = "FAILED";
            m.error = bounded(e.getMessage(), 2000);
            if (!"LEARNING".equals(m.source)) resolveUncertainTools(m.sessionId);
            store.update(m, "RUNNING");
        } finally {
            if (deadline != null) deadline.cancel(false);
            active.remove(m.agentId);
        }
    }

    static String messageRunId(Inbox message) {
        return "personal_" + RunnerService.hash(message.id).substring(0, 40);
    }

    static String text(String value, String label, int max) {
        if (value == null || value.trim().isEmpty() || value.length() > max)
            throw new IllegalArgumentException(label + " must contain 1 to " + max + " characters");
        return value.trim();
    }

    static String bounded(String text, int max) {
        return text == null ? "" : text.substring(0, Math.min(max, text.length()));
    }

    static ProtocolException conflict() {
        return new ProtocolException(
                409, "EDIT_CONFLICT", "This record changed. Reload it before saving.");
    }

    @PreDestroy
    public void close() {
        closed = true;
        for (TurnStop stop : active.values()) stop.stop();
        clock.shutdownNow();
        workers.shutdownNow();
        projects.shutdownNow();
    }

    private static class TurnStop implements StopSignal {
        private boolean stopped;
        final boolean review;

        TurnStop(boolean review) {
            this.review = review;
        }

        private final List<Runnable> callbacks = new ArrayList<>();

        public synchronized boolean isAborted() {
            return stopped;
        }

        public void throwIfAborted() {
            if (isAborted()) throw new StopRequestedException();
        }

        public synchronized StopRegistration onStop(Runnable action) {
            if (stopped) action.run();
            else callbacks.add(action);
            return () -> {
                synchronized (this) {
                    callbacks.remove(action);
                }
            };
        }

        synchronized void stop() {
            stopped = true;
            for (Runnable action : new ArrayList<>(callbacks))
                try {
                    action.run();
                } catch (RuntimeException ignored) {
                }
            callbacks.clear();
        }
    }
}

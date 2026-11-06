package io.github.differentialmanifold.jagentharness.core.personal;

import static io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentSupport.*;

import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.execution.RunService;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.project.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.*;
import java.util.concurrent.*;

/** Personal Agent API, independent of Spring and HTTP. */
public class PersonalAgentService implements AutoCloseable {
    private final PersonalAgentStore store;
    private final SessionRepository records;
    private final SessionManager sessions;
    private final SessionStore history;
    private final PersonalAgentKnowledge knowledge;
    private final PersonalTaskPlan plans;
    private final PersonalAgentProjects projects;
    private final PersonalAgentRuntime runtime;

    public PersonalAgentService(
            PersonalAgentStore store,
            SessionRepository records,
            SessionManager sessions,
            SessionStore history,
            AgentHarness harness,
            ProjectService projects,
            RunService runs,
            PersonalAgentKnowledge knowledge,
            PersonalAgentLearning learning) {
        this.store = store;
        this.records = records;
        this.sessions = sessions;
        this.history = history;
        this.knowledge = knowledge;
        this.plans = new PersonalTaskPlan(store, records, history);
        this.projects = new PersonalAgentProjects(this, store, plans, projects, runs);
        this.runtime =
                new PersonalAgentRuntime(
                        this, store, records, history, harness, learning, plans, this.projects);
    }

    public void start() {
        runtime.start();
    }

    public void tick() {
        runtime.tick();
    }

    public void close() {
        runtime.close();
    }

    public Profile require(String id) {
        Profile p = store.profile(id);
        if (p == null)
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.NOT_FOUND, "AGENT_NOT_FOUND", "Agent not found");
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
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.NOT_FOUND,
                    "CONVERSATION_NOT_FOUND",
                    "Conversation not found for this Agent");
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
        if (runtime.isActive(agent)
                || store.messages(session).stream().anyMatch(m -> "RUNNING".equals(m.status)))
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.CONFLICT,
                    "AGENT_BUSY",
                    "Wait for this Agent's current turn to finish before deleting a chat.");
        store.deleteConversation(c, () -> sessions.deleteSession(session));
    }

    public synchronized Inbox send(String agent, String session, String content, String id) {
        if (!require(agent).enabled)
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.CONFLICT,
                    "AGENT_PAUSED",
                    "Resume this Agent before sending messages");
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

    /** Incremental, persisted conversation history for headless clients. */
    public Map<String, Object> historyPage(String agent, String session, String after, int limit) {
        if (limit < 1 || limit > 100)
            throw new IllegalArgumentException("limit must be between 1 and 100");
        List<AgentMessage> messages = history(agent, session);
        int start = 0;
        if (after != null && !after.isEmpty()) {
            start = -1;
            for (int i = 0; i < messages.size(); i++)
                if (after.equals(messages.get(i).getMessageId())) {
                    start = i + 1;
                    break;
                }
            if (start < 0)
                throw new IllegalArgumentException("History cursor not found in this conversation");
        }
        int end = Math.min(messages.size(), start + limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session);
        result.put("messages", messages.subList(start, end));
        result.put("nextAfter", end == 0 ? null : messages.get(end - 1).getMessageId());
        result.put("hasMore", end < messages.size());
        return result;
    }

    /** A completed message is a completed decision turn, not proof that the user's goal is done. */
    public Map<String, Object> status(String agent, String session, String messageId) {
        Conversation conversation = conversation(agent, session);
        List<Inbox> messages = store.messages(session);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agent);
        result.put("sessionId", session);
        result.put("title", conversation.title);
        result.put("enabled", require(agent).enabled);
        result.put(
                "queuedMessages",
                messages.stream()
                        .filter(m -> !"LEARNING".equals(m.source) && "PENDING".equals(m.status))
                        .count());
        result.put(
                "runningMessages",
                messages.stream()
                        .filter(m -> !"LEARNING".equals(m.source) && "RUNNING".equals(m.status))
                        .count());
        result.put(
                "pendingProjectRuns",
                messages.stream()
                        .filter(m -> "PROJECT".equals(m.source) && "WAITING".equals(m.status))
                        .count());
        List<Card> tasks =
                cards(agent).stream()
                        .filter(c -> session.equals(c.conversationId))
                        .collect(java.util.stream.Collectors.toList());
        result.put("openTasks", tasks.stream().filter(c -> !PersonalTaskPlan.terminal(c)).count());
        result.put("completedTasks", tasks.stream().filter(c -> "DONE".equals(c.status)).count());
        result.put(
                "cancelledTasks", tasks.stream().filter(c -> "CANCELLED".equals(c.status)).count());
        result.put(
                "nextCheckAt",
                tasks.stream()
                        .filter(c -> !PersonalTaskPlan.terminal(c) && c.nextCheckAt > 0)
                        .mapToLong(c -> c.nextCheckAt)
                        .min()
                        .orElse(0));
        if (messageId != null && !messageId.isEmpty()) {
            Inbox message = store.message(messageId);
            if (message == null
                    || !agent.equals(message.agentId)
                    || !session.equals(message.sessionId))
                throw new PersonalAgentException(
                        PersonalAgentException.Kind.NOT_FOUND,
                        "MESSAGE_NOT_FOUND",
                        "Message not found in this conversation");
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("id", message.id);
            receipt.put("status", message.status);
            receipt.put("answer", message.answer);
            receipt.put("error", message.error);
            result.put("message", receipt);
        }
        return result;
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
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.NOT_FOUND,
                    "TASK_NOT_FOUND",
                    "Task not found for this Agent");
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
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.NOT_FOUND, "CARD_NOT_FOUND", "Card not found");
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

    public List<Conversation> conversations(String agent) {
        require(agent);
        return store.conversations(agent);
    }

    public List<SessionRecord> projectSessions(String agent) {
        return projects.list(agent);
    }

    public List<RunnerInfo> runners() {
        return projects.runners();
    }

    public SessionRecord createProjectSession(String agent, CreateSessionRequest request) {
        return projects.create(agent, request);
    }

    public SessionRecord requireProject(String agent, String session) {
        return projects.requireProject(agent, session);
    }

    public Map<String, Object> inspectProject(String agent, String session, String before) {
        return projects.inspect(agent, session, before);
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
        return projects.submit(agent, conversation, session, text, replyTo, taskId, readOnly);
    }

    public synchronized Object steerProject(String agent, String session, String run, String text) {
        return projects.steer(agent, session, run, text);
    }

    public synchronized Map<String, Object> stopProject(String agent, String session, String run) {
        return projects.stop(agent, session, run);
    }

    public static String messageRunId(Inbox message) {
        return "personal_" + hash(message.id).substring(0, 40);
    }
}

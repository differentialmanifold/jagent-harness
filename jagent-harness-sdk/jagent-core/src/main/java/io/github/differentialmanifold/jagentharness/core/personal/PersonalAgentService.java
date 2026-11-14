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
    private io.github.differentialmanifold.jagentharness.core.model.ModelCatalog models;

    public PersonalAgentService withModels(
            io.github.differentialmanifold.jagentharness.core.model.ModelCatalog models) {
        this.models = models;
        return this;
    }

    private String selectedModel(String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) return null;
        if (models == null) throw new IllegalArgumentException("Model catalog is unavailable");
        return models.resolveRequested(modelId);
    }

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
        p.description = PersonalAgentKnowledge.limited(p.description, 4000, "Description");
        if (p.id == null && (p.runnerId == null || p.runnerId.trim().isEmpty()))
            throw new IllegalArgumentException("Choose a Runner for this Agent");
        if (p.id != null
                && require(p.id).runnerId != null
                && !require(p.id).runnerId.equals(p.runnerId))
            throw new IllegalArgumentException(
                    "An Agent stays bound to its Runner; create another Agent for a different environment");
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
        boolean creating = p.id == null;
        if (creating) {
            p.id = Ids.newId("agent");
            p.revision = 0;
        } else require(p.id);
        knowledge.saveProfile(p);
        if (creating && p.runnerId != null && store.primaryAgentId() == null)
            store.setPrimaryAgentId(p.id, null);
        return p;
    }

    public Map<String, String> defaults() {
        Map<String, String> result = new LinkedHashMap<>();
        String primary = store.primaryAgentId();
        Profile profile = primary == null ? null : store.profile(primary);
        result.put("primaryAgentId", profile == null ? null : profile.id);
        result.put("primaryRunnerId", profile == null ? null : profile.runnerId);
        return result;
    }

    public Map<String, String> selectPrimaryAgent(String id, String expectedId) {
        Profile profile = require(id);
        if (profile.runnerId == null)
            throw new IllegalArgumentException("The primary Agent needs a bound Runner");
        if (!store.setPrimaryAgentId(id, expectedId))
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.CONFLICT,
                    "PRIMARY_AGENT_CHANGED",
                    "The primary Agent changed. Refresh and try again.");
        return defaults();
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
        title =
                text(
                        title == null ? "Chat " + (store.conversations(agent).size() + 1) : title,
                        "title",
                        256);
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
        return send(agent, session, content, id, null, Collections.emptyList());
    }

    public synchronized Inbox send(
            String agent,
            String session,
            String content,
            String id,
            String modelId,
            List<MessageImage> images) {
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
        m.images = ImageInputValidator.normalize(images);
        m.modelId = modelId;
        m.text =
                text(
                        (content == null || content.trim().isEmpty()) && !m.images.isEmpty()
                                ? "Please examine the attached images."
                                : content,
                        "text",
                        32000);
        m.createdAt = System.currentTimeMillis();
        if (!store.enqueue(m)) {
            Inbox previous = store.message(m.id);
            if (!agent.equals(previous.agentId)
                    || !session.equals(previous.sessionId)
                    || !m.text.equals(previous.text)
                    || !Objects.equals(m.modelId, previous.modelId)
                    || !new com.fasterxml.jackson.databind.ObjectMapper()
                            .valueToTree(m.images)
                            .equals(
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                            .valueToTree(previous.images))) throw conflict();
            return previous;
        }
        return m;
    }

    String requestedModel(String agent, String session) {
        if (session == null) return null;
        for (Inbox input : store.messages(session))
            if (agent.equals(input.agentId) && "RUNNING".equals(input.status)) return input.modelId;
        return null;
    }

    public Map<String, Object> agentDescription(Profile p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentId", p.id);
        out.put("name", p.name);
        out.put("description", p.description);
        out.put("runnerId", p.runnerId);
        out.put("enabled", p.enabled);
        return out;
    }

    public Map<String, Object> discoverAgents(String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> available = new ArrayList<>();
        Map<String, Map<String, Object>> environments = new LinkedHashMap<>();
        for (RunnerInfo runner : runners()) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("runnerId", runner.runnerId);
            env.put("name", runner.name);
            env.put("description", runner.description);
            env.put("platform", runner.platform);
            env.put("capabilities", runner.capabilities);
            env.put("online", runner.online);
            env.put("workspaces", runner.workspaces);
            env.put("workspaceRoot", runner.workspaceRoot);
            env.put(
                    "tools",
                    runner.tools.stream()
                            .map(t -> t.name)
                            .collect(java.util.stream.Collectors.toList()));
            environments.put(runner.runnerId, env);
        }
        for (Profile p : store.profiles()) {
            Map<String, Object> row = agentDescription(p);
            row.put("environment", environments.get(p.runnerId));
            if (row.toString().toLowerCase(Locale.ROOT).contains(needle)) available.add(row);
        }
        out.put(
                "agents",
                available.stream().limit(50).collect(java.util.stream.Collectors.toList()));
        out.put(
                "runners",
                environments.values().stream()
                        .filter(r -> r.toString().toLowerCase(Locale.ROOT).contains(needle))
                        .limit(50)
                        .collect(java.util.stream.Collectors.toList()));
        return out;
    }

    public synchronized Map<String, Object> sendToAgent(
            String from,
            String origin,
            String recipient,
            String session,
            String content,
            String taskId,
            ReplyTarget returnTo) {
        return sendToAgent(from, origin, recipient, session, content, taskId, returnTo, null);
    }

    public synchronized Map<String, Object> sendToAgent(
            String from,
            String origin,
            String recipient,
            String session,
            String content,
            String taskId,
            ReplyTarget returnTo,
            String modelId) {
        modelId = selectedModel(modelId);
        Conversation source = conversation(from, origin);
        Profile target = require(recipient);
        if (!target.enabled) throw new IllegalArgumentException("Recipient Agent is paused");
        List<String> chain = new ArrayList<>(source.delegationChain);
        if (!chain.contains(from)) chain.add(from);
        if (chain.contains(recipient))
            throw new IllegalArgumentException(
                    "Delegation would cycle back to an ancestor; reply to the existing request instead");
        Card task = taskId == null ? null : plans.require(from, taskId);
        if (task != null) {
            plans.ready(task);
            if (task.conversationId != null && !origin.equals(task.conversationId))
                throw new IllegalArgumentException("Continue this task in its owning conversation");
            if (plans.delegationPending(task) && !Objects.equals(task.delegateSessionId, session))
                throw new IllegalArgumentException(
                        "This task already has delegated work; continue that conversation or wait for it");
            for (Inbox active : store.inbox("WAITING"))
                if (task.id.equals(active.taskId))
                    throw new IllegalArgumentException(
                            "Wait for this task's project execution before delegating it");
            if (PersonalTaskPlan.terminal(task))
                throw new IllegalArgumentException("Reopen the task before delegation");
        }
        Conversation dest;
        if (session == null || session.isEmpty()) {
            SessionRecord record = records.createStandalone("From " + require(from).name);
            dest = new Conversation();
            dest.agentId = recipient;
            dest.sessionId = record.getSessionId();
            dest.title = record.getTitle();
            dest.createdAt = System.currentTimeMillis();
            dest.requesterAgentId = from;
            dest.requesterSessionId = origin;
            dest.requesterReplyTo = returnTo;
            dest.requesterTaskId = taskId;
            dest.requesterModelId = requestedModel(from, origin);
            dest.delegationChain = chain;
            dest.delegationChain.add(recipient);
            store.add(dest);
        } else {
            dest = delegatedConversation(from, recipient, session);
            if (!origin.equals(dest.requesterSessionId)
                    || !Objects.equals(taskId, dest.requesterTaskId))
                throw new IllegalArgumentException(
                        "Continue in the originating conversation with the same task reference");
        }
        Inbox m = new Inbox();
        m.id = Ids.newId("msg");
        m.agentId = recipient;
        m.sessionId = dest.sessionId;
        m.source = "AGENT";
        m.modelId = modelId;
        m.senderId = from;
        m.text = text(content, "text", 32000);
        m.createdAt = System.currentTimeMillis();
        m.replyTo = new ReplyTarget("agent", from, origin);
        if (task != null) {
            task.delegateAgentId = recipient;
            task.delegateSessionId = dest.sessionId;
            task.delegateMessageId = m.id;
            task.delegateRequirements = plans.requirements(task);
            task.lastRunId = null;
            task.evidence.clear();
            task.status = "WAITING";
            task.progress =
                    "Delegated to "
                            + target.name
                            + "; waiting for evidence, not just acknowledgement.";
            if (!store.submitTask(task, m)) throw conflict();
        } else store.enqueue(m);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", recipient);
        result.put("sessionId", dest.sessionId);
        result.put("messageId", m.id);
        result.put("status", "ACCEPTED");
        return result;
    }

    private Conversation delegatedConversation(String requester, String agent, String session) {
        Conversation c = conversation(agent, session);
        if (!requester.equals(c.requesterAgentId))
            throw new IllegalArgumentException("This delegation belongs to another Agent");
        return c;
    }

    public Map<String, Object> inspectDelegation(String requester, String agent, String session) {
        delegatedConversation(requester, agent, session);
        List<AgentMessage> replies = new ArrayList<>();
        for (AgentMessage m : history.findMessages(session))
            if ("assistant".equals(m.getRole())
                    && (m.getToolCalls() == null || m.getToolCalls().isEmpty())) {
                AgentMessage visible = m.copy();
                visible.setReasoningContent(null);
                replies.add(visible);
            }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentId", agent);
        out.put("sessionId", session);
        out.put("replies", replies.subList(Math.max(0, replies.size() - 40), replies.size()));
        out.put(
                "pending",
                store.messages(session).stream()
                        .anyMatch(
                                m ->
                                        Arrays.asList("PENDING", "RUNNING", "WAITING")
                                                .contains(m.status)));
        return out;
    }

    void deliverAgentReplies() {
        for (String status : Arrays.asList("DONE", "FAILED"))
            for (Inbox m : store.inbox(status)) {
                if (m.delivered || m.replyTo == null || !"agent".equals(m.replyTo.channel))
                    continue;
                Conversation delegated = store.conversation(m.sessionId);
                if (delegated == null
                        || !Objects.equals(delegated.requesterAgentId, m.replyTo.accountId)
                        || !Objects.equals(delegated.requesterSessionId, m.replyTo.recipientId))
                    continue;
                if (store.conversation(m.replyTo.recipientId) == null) {
                    m.delivered = true;
                    store.update(m, status);
                    continue;
                }
                Inbox reply = new Inbox();
                reply.id = "reply_" + m.id;
                reply.agentId = m.replyTo.accountId;
                reply.sessionId = m.replyTo.recipientId;
                reply.source = "AGENT_REPLY";
                reply.senderId = m.agentId;
                reply.text =
                        "Reply from "
                                + require(m.agentId).name
                                + " (conversation "
                                + m.sessionId
                                + "):\n"
                                + ("FAILED".equals(status)
                                        ? "Execution failed: " + m.error
                                        : m.answer);
                reply.createdAt = System.currentTimeMillis();
                reply.replyTo = delegated.requesterReplyTo;
                reply.taskId = delegated.requesterTaskId;
                reply.modelId = delegated.requesterModelId;
                store.enqueue(reply);
                m.delivered = true;
                store.update(m, status);
            }
    }

    public SessionRecord openProjectSession(
            String agent, CreateSessionRequest request, boolean newSession) {
        return projects.open(agent, request, newSession);
    }

    public List<Map<String, Object>> projectCatalog(String agent) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (SessionRecord session : projectSessions(agent)) {
            Map<String, Object> row =
                    grouped.computeIfAbsent(
                            session.getProjectId(),
                            key -> {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("projectId", key);
                                item.put("name", session.getProjectName());
                                item.put("workspacePath", session.getWorkspacePath());
                                item.put("runnerId", session.getRunnerId());
                                item.put("sessions", new ArrayList<>());
                                return item;
                            });
            Map<String, Object> chat = new LinkedHashMap<>();
            chat.put("sessionId", session.getSessionId());
            chat.put("title", session.getTitle());
            ((List<Object>) row.get("sessions")).add(chat);
        }
        return new ArrayList<>(grouped.values());
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
        c.modelId = old == null ? requestedModel(agent, c.conversationId) : old.modelId;
        c.title = text(c.title, "title", 512);
        require(c.ownerId);
        c.progress = bounded(c.progress, 16000);
        c.lastRunId = old == null ? null : old.lastRunId;
        c.delegateAgentId = old == null ? null : old.delegateAgentId;
        c.delegateSessionId = old == null ? null : old.delegateSessionId;
        c.delegateMessageId = old == null ? null : old.delegateMessageId;
        c.delegateRequirements = old == null ? null : old.delegateRequirements;
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
        return submitProject(agent, conversation, session, text, replyTo, taskId, readOnly, null);
    }

    synchronized Inbox submitProject(
            String agent,
            String conversation,
            String session,
            String text,
            ReplyTarget replyTo,
            String taskId,
            boolean readOnly,
            String modelId) {
        return projects.submit(
                agent,
                conversation,
                session,
                text,
                replyTo,
                taskId,
                readOnly,
                selectedModel(modelId));
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

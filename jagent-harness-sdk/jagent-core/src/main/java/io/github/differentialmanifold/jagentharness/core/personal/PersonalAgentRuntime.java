package io.github.differentialmanifold.jagentharness.core.personal;

import static io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentSupport.*;

import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs persisted Agent events, scheduled checks and recovery without a Web container. */
final class PersonalAgentRuntime {
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(PersonalAgentRuntime.class.getName());
    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final PersonalAgentService agents;
    private final PersonalAgentStore store;
    private final SessionRepository records;
    private final SessionStore history;
    private final AgentHarness harness;
    private final PersonalAgentLearning learning;
    private final PersonalTaskPlan plans;
    private final PersonalAgentProjects projects;
    private final ScheduledExecutorService clock =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "personal-inbox"));
    private final ExecutorService workers =
            Executors.newCachedThreadPool(r -> daemon(r, "personal-agent"));
    private final ConcurrentMap<String, TurnStop> active = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile boolean failed;

    PersonalAgentRuntime(
            PersonalAgentService agents,
            PersonalAgentStore store,
            SessionRepository records,
            SessionStore history,
            AgentHarness harness,
            PersonalAgentLearning learning,
            PersonalTaskPlan plans,
            PersonalAgentProjects projects) {
        this.agents = agents;
        this.store = store;
        this.records = records;
        this.history = history;
        this.harness = harness;
        this.learning = learning;
        this.plans = plans;
        this.projects = projects;
    }

    private Profile require(String id) {
        return agents.require(id);
    }

    boolean isActive(String agent) {
        return active.containsKey(agent);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public void start() {
        synchronized (agents) {
            restoreProjectOwnership();
            recoverInterrupted();
            clock.scheduleWithFixedDelay(this::tick, 500, 1000, TimeUnit.MILLISECONDS);
        }
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
                recovery.id = "recover_" + hash(m.id).substring(0, 40);
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
                            LOG.log(
                                    java.util.logging.Level.WARNING,
                                    "Could not restore project ownership from " + m.getMessageId(),
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
                + plans.context(event.agentId, event.sessionId, event.taskId)
                + "\nRelevant projects (configuration data): "
                + json.valueToTree(
                        agents.projectCatalog(event.agentId).stream()
                                .limit(12)
                                .map(
                                        project -> {
                                            Map<String, Object> summary =
                                                    new LinkedHashMap<>(project);
                                            summary.put(
                                                    "sessions",
                                                    ((List<?>) project.get("sessions"))
                                                            .stream()
                                                                    .limit(3)
                                                                    .collect(
                                                                            java.util.stream
                                                                                    .Collectors
                                                                                    .toList()));
                                            return summary;
                                        })
                                .collect(java.util.stream.Collectors.toList()))
                + "\nBound Runner (environment data): "
                + json.valueToTree(
                        agents.runners().stream()
                                .filter(
                                        r ->
                                                Objects.equals(
                                                        r.runnerId,
                                                        require(event.agentId).runnerId))
                                .map(
                                        r -> {
                                            Map<String, Object> out = new LinkedHashMap<>();
                                            out.put("name", r.name);
                                            out.put("platform", r.platform);
                                            out.put("description", r.description);
                                            out.put("capabilities", r.capabilities);
                                            out.put("online", r.online);
                                            return out;
                                        })
                                .findFirst()
                                .orElse(Collections.emptyMap()));
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
            next.modelId = c.modelId;
            next.replyTo = c.replyTo;
            next.createdAt = System.currentTimeMillis();
            next.text =
                    "Task "
                            + c.id
                            + " has unfinished work and no outstanding execution or scheduled check. Inspect existing evidence, then advance the plan, verify results, or record a blocker as WAITING. Do not repeat a run merely because its outcome is uncertain.";
            if (store.enqueue(next)) occupied.add(c.ownerId);
        }
    }

    public void tick() {
        synchronized (agents) {
            if (closed) return;
            try {
                for (Map.Entry<String, TurnStop> entry : active.entrySet())
                    if (!require(entry.getKey()).enabled) entry.getValue().stop();
                recoverInterrupted();
                projects.checkResults();
                agents.deliverAgentReplies();
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
                        check.modelId = c.modelId;
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
                                                "AGENT",
                                                "AGENT_REPLY",
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
                            && active.putIfAbsent(
                                            m.agentId, new TurnStop("LEARNING".equals(m.source)))
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
                    LOG.log(
                            java.util.logging.Level.WARNING,
                            "Personal Agent inbox check failed; persisted work remains available",
                            e);
                failed = true;
            }
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
            String runId = PersonalAgentService.messageRunId(m);
            AgentMessage inputMessage = AgentMessage.user(m.sessionId, input);
            inputMessage.setImages(m.images);
            Map<String, Object> origin = new LinkedHashMap<>();
            boolean peerMessage = "AGENT".equals(m.source) || "AGENT_REPLY".equals(m.source);
            origin.put("kind", peerMessage ? "agent_message" : "personal_message");
            origin.put("source", m.source);
            if (peerMessage) {
                origin.put("senderAgentId", m.senderId);
                origin.put("senderName", require(m.senderId).name);
            }
            inputMessage.setMetadataJson(json.writeValueAsString(origin));
            AgentRunResult result =
                    harness.run(
                            m.sessionId,
                            Collections.singletonList(inputMessage),
                            AgentRunOptions.builder()
                                    .modelId(m.modelId)
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
                                                                    && !"FEISHU".equals(m.source)
                                                                    && !"AGENT".equals(m.source)))
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
                                                        LOG.log(
                                                                java.util.logging.Level.WARNING,
                                                                "Could not capture learning checkpoint for "
                                                                        + m.id,
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
                    LOG.log(
                            java.util.logging.Level.WARNING,
                            "Could not queue learning review for " + m.id,
                            e);
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

    public void close() {
        closed = true;
        for (TurnStop stop : active.values()) stop.stop();
        clock.shutdownNow();
        workers.shutdownNow();
        projects.close();
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

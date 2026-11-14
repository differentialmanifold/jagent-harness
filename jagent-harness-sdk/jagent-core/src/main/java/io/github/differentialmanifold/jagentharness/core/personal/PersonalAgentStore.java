package io.github.differentialmanifold.jagentharness.core.personal;

import java.util.*;

/** Persistence for personal assistants; projects and their execution remain independent. */
public interface PersonalAgentStore {
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    class Profile {
        public String id, name, description = "", instructions = "", memory = "";
        public String runnerId, workspacePath;
        public boolean enabled = true;
        public List<String> disabledTools = new ArrayList<>();
        public long revision;
    }

    class Conversation {
        public String sessionId, agentId, title;
        public long createdAt;
        public boolean running;
        public String requesterAgentId, requesterSessionId, requesterTaskId, requesterModelId;
        public ReplyTarget requesterReplyTo;
        public List<String> delegationChain = new ArrayList<>();
    }

    /** Original external destination, persisted across project results and scheduled checks. */
    class ReplyTarget {
        public String channel, accountId, recipientId;

        public ReplyTarget() {}

        public ReplyTarget(String channel, String accountId, String recipientId) {
            this.channel = channel;
            this.accountId = accountId;
            this.recipientId = recipientId;
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    class Inbox {
        public String id, agentId, sessionId, source, senderId, text;
        public String runId, modelId;
        public List<io.github.differentialmanifold.jagentharness.core.message.MessageImage> images =
                new ArrayList<>();
        public String status = "PENDING", answer = "", error = "";
        public String projectSessionId, projectRunId, projectModelId;
        public String taskId, taskRequirements, executionScope;
        public boolean readOnly, dispatchStarted;
        public long createdAt;
        public boolean delivered;
        public int deliveryAttempts;
        public long nextDeliveryAt;
        public String deliveryError;
        public Long knowledgeRevision;
        public ReplyTarget replyTo;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    class Card {
        public String id, title, ownerId, status = "TODO", progress = "";
        public String projectId, sessionId, conversationId;
        public String modelId,
                delegateAgentId,
                delegateSessionId,
                delegateMessageId,
                delegateRequirements;
        public String parentId, description = "", acceptance = "", lastRunId;
        public List<String> dependsOn = new ArrayList<>();
        public List<Evidence> evidence = new ArrayList<>();
        public long nextCheckAt, revision;
        public ReplyTarget replyTo;
    }

    class Evidence {
        public String sessionId, messageId, note;
    }

    /** Atomically update the task and enqueue its execution intent before dispatch. */
    boolean submitTask(Card task, Inbox execution);

    /** Mark an interrupted turn failed and enqueue its recovery decision atomically. */
    boolean recoverTurn(Inbox interrupted, Inbox recovery);

    /** One reversible knowledge edit. Null content denotes an absent skill file. */
    class LearningChange {
        public String id, agentId, path, reason, origin, sessionId, messageId, revertsId;
        public String beforeContent, afterContent;
        public long revision, createdAt;
    }

    /**
     * Atomically CAS the profile, update transaction-aware knowledge storage, and append history.
     */
    boolean saveLearning(Profile profile, LearningChange change, Runnable updateKnowledge);

    List<LearningChange> changes(String agentId, long beforeRevision, int limit);

    LearningChange change(String agentId, String changeId);

    LearningChange latestChange(String agentId, String path);

    /** The default user-facing Agent; its bound Runner is also the default Runner. */
    String primaryAgentId();

    /** Compare-and-set the default; null expectedId initializes it once. */
    boolean setPrimaryAgentId(String agentId, String expectedId);

    List<Profile> profiles();

    Profile profile(String id);
    /** Compare-and-set; revision zero creates a record. */
    boolean save(Profile profile);

    List<Conversation> conversations(String agentId);

    Conversation conversation(String sessionId);

    void add(Conversation conversation);

    /** Update both conversation metadata and the underlying session in one transaction. */
    void renameConversation(Conversation conversation, Runnable renameSession);

    /** Detach scheduled work and channel bindings; retain projects and learned knowledge. */
    void deleteConversation(Conversation conversation, Runnable deleteSession);

    List<Inbox> inbox(String status);

    List<Inbox> messages(String sessionId);

    Inbox message(String id);
    /** Returns false for a previously accepted message ID. */
    boolean enqueue(Inbox message);

    boolean update(Inbox message, String expectedStatus);
    /**
     * Atomically clear a due check and enqueue its notification if the card revision still matches.
     */
    boolean scheduleCheck(Card card, Inbox notification);

    List<Card> cards();

    Card card(String id);

    boolean save(Card card);
    /** Channel credentials are deliberately separate from model-visible profile data. */
    String channel(String agentId);

    void channel(String agentId, String configuration);
}

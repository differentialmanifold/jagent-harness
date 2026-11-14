package io.github.differentialmanifold.jagentharness.store.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcPersonalAgentStore implements PersonalAgentStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String application;

    public JdbcPersonalAgentStore(
            JdbcTemplate db, ObjectMapper json, JdbcStoreProperties properties) {
        this.db = db;
        this.json = json;
        this.application = properties.requireApplicationId();
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode personal agent record", e);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read personal agent record", e);
        }
    }

    private <T> List<T> query(String sql, Class<T> type, Object... args) {
        return db.query(sql, (rs, row) -> decode(rs.getString("body_json"), type), args);
    }

    private boolean duplicate(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException) return true;
            if (cause instanceof java.sql.SQLException) {
                java.sql.SQLException sql = (java.sql.SQLException) cause;
                if ("23505".equals(sql.getSQLState())) return true;
                if (sql.getErrorCode() == 19
                        && (sql.getMessage().contains("SQLITE_CONSTRAINT_PRIMARYKEY")
                                || sql.getMessage().contains("SQLITE_CONSTRAINT_UNIQUE")))
                    return true;
            }
        }
        return false;
    }

    private <T> T first(List<T> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    public String primaryAgentId() {
        List<String> ids =
                db.query(
                        "select primary_agent_id from personal_settings where application_id=?",
                        (rs, row) -> rs.getString(1),
                        application);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public boolean setPrimaryAgentId(String agentId, String expectedId) {
        if (agentId == null) throw new IllegalArgumentException("Primary Agent is required");
        if (expectedId != null)
            return db.update(
                            "update personal_settings set primary_agent_id=? where application_id=? and primary_agent_id=?",
                            agentId,
                            application,
                            expectedId)
                    == 1;
        try {
            db.update(
                    "insert into personal_settings(application_id, primary_agent_id) values(?,?)",
                    application,
                    agentId);
            return true;
        } catch (DataAccessException error) {
            if (duplicate(error)) return false;
            throw error;
        }
    }

    public List<Profile> profiles() {
        return query(
                "select body_json from personal_agents where application_id = ? order by agent_id",
                Profile.class,
                application);
    }

    public Profile profile(String id) {
        return first(
                query(
                        "select body_json from personal_agents where application_id = ? and agent_id = ?",
                        Profile.class,
                        application,
                        id));
    }

    public boolean save(Profile p) {
        long revision = p.revision;
        p.revision++;
        try {
            if (revision == 0)
                db.update(
                        "insert into personal_agents(application_id, agent_id, revision, body_json) values(?,?,?,?)",
                        application,
                        p.id,
                        p.revision,
                        encode(p));
            else if (db.update(
                            "update personal_agents set revision=?, body_json=? where application_id=? and agent_id=? and revision=?",
                            p.revision,
                            encode(p),
                            application,
                            p.id,
                            revision)
                    == 0) {
                p.revision = revision;
                return false;
            }
            return true;
        } catch (DataAccessException e) {
            p.revision = revision;
            if (duplicate(e)) return false;
            throw e;
        }
    }

    public List<Conversation> conversations(String agent) {
        return query(
                "select body_json from personal_conversations where application_id=? and agent_id=? order by created_at",
                Conversation.class,
                application,
                agent);
    }

    public boolean saveLearning(Profile profile, LearningChange change, Runnable updateKnowledge) {
        long revision = profile.revision;
        try {
            return Boolean.TRUE.equals(
                    JdbcTransactions.write(db)
                            .execute(
                                    status -> {
                                        if (!save(profile)) return false;
                                        updateKnowledge.run();
                                        change.revision = profile.revision;
                                        db.update(
                                                "insert into personal_learning_changes "
                                                        + "(application_id, agent_id, change_id, revision, path, body_json) values(?,?,?,?,?,?)",
                                                application,
                                                profile.id,
                                                change.id,
                                                change.revision,
                                                change.path,
                                                encode(change));
                                        return true;
                                    }));
        } catch (RuntimeException e) {
            profile.revision = revision;
            throw e;
        }
    }

    public List<LearningChange> changes(String agent, long beforeRevision, int limit) {
        return query(
                "select body_json from personal_learning_changes where application_id=? and agent_id=? "
                        + "and revision < ? order by revision desc limit ?",
                LearningChange.class,
                application,
                agent,
                beforeRevision <= 0 ? Long.MAX_VALUE : beforeRevision,
                Math.min(100, Math.max(1, limit)));
    }

    public LearningChange change(String agent, String id) {
        return first(
                query(
                        "select body_json from personal_learning_changes where application_id=? and agent_id=? and change_id=?",
                        LearningChange.class,
                        application,
                        agent,
                        id));
    }

    public LearningChange latestChange(String agent, String path) {
        return first(
                query(
                        "select body_json from personal_learning_changes where application_id=? and agent_id=? and path=? order by revision desc limit 1",
                        LearningChange.class,
                        application,
                        agent,
                        path));
    }

    public Conversation conversation(String session) {
        return first(
                query(
                        "select body_json from personal_conversations where application_id=? and session_id=?",
                        Conversation.class,
                        application,
                        session));
    }

    public void add(Conversation c) {
        db.update(
                "insert into personal_conversations(application_id, session_id, agent_id, created_at, body_json) values(?,?,?,?,?)",
                application,
                c.sessionId,
                c.agentId,
                c.createdAt,
                encode(c));
    }

    public void renameConversation(Conversation c, Runnable renameSession) {
        transaction(
                () -> {
                    if (db.update(
                                    "update personal_conversations set body_json=? where application_id=? and session_id=? and agent_id=?",
                                    encode(c),
                                    application,
                                    c.sessionId,
                                    c.agentId)
                            != 1) throw new IllegalArgumentException("Conversation not found");
                    renameSession.run();
                });
    }

    public void deleteConversation(Conversation c, Runnable deleteSession) {
        transaction(
                () -> {
                    for (Card card : cards()) {
                        if (!c.agentId.equals(card.ownerId)
                                || !c.sessionId.equals(card.conversationId)) continue;
                        card.conversationId = null;
                        card.nextCheckAt = 0;
                        if (!"DONE".equals(card.status)) {
                            card.status = "WAITING";
                            card.progress =
                                    "Chat deleted. Choose another chat to resume follow-up.\n"
                                            + card.progress;
                        }
                        if (!save(card))
                            throw new IllegalStateException(
                                    "Task changed; retry deleting the chat");
                    }
                    String channel = channel(c.agentId);
                    if (channel != null && !channel.trim().isEmpty()) {
                        try {
                            com.fasterxml.jackson.databind.node.ObjectNode config =
                                    (com.fasterxml.jackson.databind.node.ObjectNode)
                                            json.readTree(channel);
                            if (c.sessionId.equals(config.path("sessionId").asText())) {
                                config.putNull("sessionId");
                                channel(c.agentId, encode(config));
                            }
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException("Cannot update channel binding", e);
                        }
                    }
                    db.update(
                            "delete from personal_inbox where application_id=? and session_id=?",
                            application,
                            c.sessionId);
                    db.update(
                            "delete from personal_conversations where application_id=? and session_id=? and agent_id=?",
                            application,
                            c.sessionId,
                            c.agentId);
                    deleteSession.run();
                });
    }

    private void transaction(Runnable operation) {
        JdbcTransactions.write(db)
                .execute(
                        status -> {
                            operation.run();
                            return null;
                        });
    }

    public List<Inbox> inbox(String status) {
        return query(
                "select body_json from personal_inbox where application_id=? and status=? order by created_at, message_id",
                Inbox.class,
                application,
                status);
    }

    public List<Inbox> messages(String session) {
        return query(
                "select body_json from personal_inbox where application_id=? and session_id=? order by created_at, message_id",
                Inbox.class,
                application,
                session);
    }

    public Inbox message(String id) {
        return first(
                query(
                        "select body_json from personal_inbox where application_id=? and message_id=?",
                        Inbox.class,
                        application,
                        id));
    }

    public boolean enqueue(Inbox m) {
        try {
            db.update(
                    "insert into personal_inbox(application_id, message_id, session_id, status, created_at, body_json) values(?,?,?,?,?,?)",
                    application,
                    m.id,
                    m.sessionId,
                    m.status,
                    m.createdAt,
                    encode(m));
            return true;
        } catch (DataAccessException e) {
            if (duplicate(e)) return false;
            throw e;
        }
    }

    public boolean update(Inbox m, String expectedStatus) {
        return db.update(
                        "update personal_inbox set status=?, body_json=? where application_id=? and message_id=? and status=?",
                        m.status,
                        encode(m),
                        application,
                        m.id,
                        expectedStatus)
                == 1;
    }

    public boolean recoverTurn(Inbox interrupted, Inbox recovery) {
        final boolean[] changed = {false};
        transaction(
                () -> {
                    changed[0] = update(interrupted, "RUNNING");
                    if (changed[0]) enqueue(recovery);
                });
        return changed[0];
    }

    public boolean submitTask(Card card, Inbox execution) {
        long revision = card.revision;
        try {
            transaction(
                    () -> {
                        if (!save(card) || !enqueue(execution))
                            throw new IllegalStateException("Task changed before dispatch");
                    });
            return true;
        } catch (IllegalStateException e) {
            card.revision = revision;
            return false;
        }
    }

    public boolean scheduleCheck(Card card, Inbox notification) {
        org.springframework.transaction.support.TransactionTemplate tx = JdbcTransactions.write(db);
        return Boolean.TRUE.equals(
                tx.execute(
                        status -> {
                            card.nextCheckAt = 0;
                            if (!save(card)) return false;
                            enqueue(notification);
                            return true;
                        }));
    }

    public List<Card> cards() {
        return query(
                "select body_json from personal_cards where application_id=? order by card_id",
                Card.class,
                application);
    }

    public Card card(String id) {
        return first(
                query(
                        "select body_json from personal_cards where application_id=? and card_id=?",
                        Card.class,
                        application,
                        id));
    }

    public boolean save(Card c) {
        long revision = c.revision;
        c.revision++;
        try {
            if (revision == 0)
                db.update(
                        "insert into personal_cards(application_id, card_id, revision, body_json) values(?,?,?,?)",
                        application,
                        c.id,
                        c.revision,
                        encode(c));
            else if (db.update(
                            "update personal_cards set revision=?, body_json=? where application_id=? and card_id=? and revision=?",
                            c.revision,
                            encode(c),
                            application,
                            c.id,
                            revision)
                    == 0) {
                c.revision = revision;
                return false;
            }
            return true;
        } catch (DataAccessException e) {
            c.revision = revision;
            if (duplicate(e)) return false;
            throw e;
        }
    }

    public String channel(String agent) {
        List<String> values =
                db.query(
                        "select channel_json from personal_agents where application_id=? and agent_id=?",
                        (rs, row) -> rs.getString(1),
                        application,
                        agent);
        return values.isEmpty() ? null : values.get(0);
    }

    public void channel(String agent, String configuration) {
        if (db.update(
                        "update personal_agents set channel_json=? where application_id=? and agent_id=?",
                        configuration,
                        application,
                        agent)
                != 1) throw new IllegalArgumentException("Agent not found");
    }
}

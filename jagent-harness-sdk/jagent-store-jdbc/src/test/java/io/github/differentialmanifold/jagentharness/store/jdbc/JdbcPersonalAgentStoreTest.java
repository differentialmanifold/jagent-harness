package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcPersonalAgentStoreTest {
    @TempDir Path temp;

    @Test
    void sqlitePersistsRecordsAndRejectsStaleUpdates() throws Exception {
        exercise("jdbc:sqlite:" + temp.resolve("personal.db"));
    }

    @Test
    void h2PersistsRecordsAndRejectsStaleUpdates() throws Exception {
        exercise("jdbc:h2:mem:personal-store;DB_CLOSE_DELAY=-1");
    }

    @Test
    void persistsTaskDispatchAndRecoveryWithSqlite() throws Exception {
        java.nio.file.Path database = java.nio.file.Files.createTempFile("personal-plan-", ".db");
        try {
            exercise("jdbc:sqlite:" + database);
        } finally {
            java.nio.file.Files.deleteIfExists(database);
        }
    }

    void exercise(String url) throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(url);
        new JdbcSchemaInitializer(source).initialize();
        JdbcTemplate db = new JdbcTemplate(source);
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId("one");
        JdbcSessionRepository sessions = new JdbcSessionRepository(db, properties);
        io.github.differentialmanifold.jagentharness.core.session.SessionRecord owned =
                sessions.create("Owned", null, "App", "owned-project", "agent-one");
        assertEquals("agent-one", sessions.findBySessionId(owned.getSessionId()).getOwnerAgentId());
        assertEquals(
                "agent-one",
                sessions.create("Follow-up", null, "App", "owned-project", "agent-one")
                        .getOwnerAgentId());
        assertThrows(
                IllegalArgumentException.class,
                () -> sessions.create("Wrong owner", null, "App", "owned-project", "agent-two"));
        assertFalse(sessions.assignProjectOwnerIfUnowned("owned-project", "agent-two"));
        io.github.differentialmanifold.jagentharness.core.session.SessionRecord legacy =
                sessions.create("Legacy", null, "Old", "legacy-project");
        assertTrue(sessions.assignProjectOwnerIfUnowned("legacy-project", "agent-one"));
        assertEquals(
                "agent-one", sessions.findBySessionId(legacy.getSessionId()).getOwnerAgentId());
        assertFalse(sessions.assignProjectOwnerIfUnowned("legacy-project", "agent-two"));
        JdbcPersonalAgentStore store =
                new JdbcPersonalAgentStore(db, new ObjectMapper(), properties);
        Profile p = new Profile();
        p.id = "agent";
        p.name = "Assistant";
        assertTrue(store.save(p));
        store.channel(p.id, "secret-configuration");
        Profile stale = store.profile(p.id);
        p.memory = "learned";
        assertTrue(store.save(p));
        stale.memory = "old";
        assertFalse(store.save(stale));
        JdbcPersonalAgentStore restarted =
                new JdbcPersonalAgentStore(db, new ObjectMapper(), properties);
        assertEquals("learned", restarted.profile(p.id).memory);
        assertEquals("secret-configuration", restarted.channel(p.id));
        assertFalse(
                new ObjectMapper().writeValueAsString(restarted.profile(p.id)).contains("secret"));
        Conversation conversation = new Conversation();
        conversation.agentId = p.id;
        conversation.sessionId = "transactional-conversation";
        conversation.title = "Before";
        store.add(conversation);
        conversation.title = "After";
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.renameConversation(
                                conversation,
                                () -> {
                                    throw new IllegalStateException("Session rename failed");
                                }));
        assertEquals("Before", store.conversation(conversation.sessionId).title);
        store.renameConversation(conversation, () -> {});
        assertEquals("After", store.conversation(conversation.sessionId).title);
        Card detached = new Card();
        detached.id = "detach-check";
        detached.ownerId = p.id;
        detached.title = "Follow up";
        detached.conversationId = conversation.sessionId;
        detached.nextCheckAt = 100;
        assertTrue(store.save(detached));
        store.channel(p.id, "{\"sessionId\":\"transactional-conversation\",\"appId\":\"app\"}");
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.deleteConversation(
                                conversation,
                                () -> {
                                    throw new IllegalStateException("Session deletion failed");
                                }));
        assertNotNull(store.conversation(conversation.sessionId));
        assertEquals(100, store.card(detached.id).nextCheckAt);
        store.deleteConversation(conversation, () -> {});
        assertNull(store.conversation(conversation.sessionId));
        assertNull(store.card(detached.id).conversationId);
        assertEquals(0, store.card(detached.id).nextCheckAt);
        assertTrue(new ObjectMapper().readTree(store.channel(p.id)).path("sessionId").isNull());
        Inbox m = new Inbox();
        m.id = "message";
        m.agentId = p.id;
        m.sessionId = "session";
        m.text = "work";
        assertTrue(store.enqueue(m));
        assertFalse(store.enqueue(m));
        Inbox a = store.message(m.id), b = store.message(m.id);
        a.status = "RUNNING";
        b.status = "RUNNING";
        assertTrue(store.update(a, "PENDING"));
        assertFalse(store.update(b, "PENDING"));
        Card card = new Card();
        card.id = "check-card";
        card.ownerId = p.id;
        card.title = "Goal";
        card.nextCheckAt = 1;
        assertTrue(store.save(card));
        Card staleCard = store.card(card.id);
        card.status = "DONE";
        assertTrue(store.save(card));
        Inbox check = new Inbox();
        check.id = "check";
        check.agentId = p.id;
        check.sessionId = "session";
        check.text = "Review";
        assertFalse(store.scheduleCheck(staleCard, check));
        assertNull(store.message(check.id));
        assertTrue(store.scheduleCheck(store.card(card.id), check));
        assertNotNull(store.message(check.id));
        assertEquals(0, store.card(card.id).nextCheckAt);
        Card submitted = store.card(card.id);
        Inbox execution = new Inbox();
        execution.id = "atomic-execution";
        execution.agentId = p.id;
        execution.sessionId = "session";
        execution.source = "PROJECT";
        execution.status = "WAITING";
        execution.taskId = card.id;
        execution.taskRequirements = "original criteria";
        execution.projectRunId = "tracked-run";
        submitted.lastRunId = execution.projectRunId;
        submitted.description = "saved with execution";
        assertTrue(store.submitTask(submitted, execution));
        assertEquals("tracked-run", restarted.card(card.id).lastRunId);
        assertEquals("original criteria", restarted.message(execution.id).taskRequirements);
        Card conflicting = store.card(card.id);
        conflicting.description = "must roll back";
        assertFalse(store.submitTask(conflicting, execution));
        assertEquals("saved with execution", store.card(card.id).description);
        Inbox orphan = new Inbox();
        orphan.id = "must-not-exist";
        orphan.sessionId = "session";
        assertFalse(store.submitTask(staleCard, orphan));
        assertNull(store.message(orphan.id));
        Inbox interrupted = store.message(m.id);
        interrupted.status = "FAILED";
        Inbox recovery = new Inbox();
        recovery.id = "recover-transaction";
        recovery.agentId = p.id;
        recovery.sessionId = "session";
        recovery.source = "RECOVERY";
        assertTrue(store.recoverTurn(interrupted, recovery));
        assertEquals("FAILED", restarted.message(m.id).status);
        assertEquals("RECOVERY", restarted.message(recovery.id).source);
        assertFalse(store.recoverTurn(interrupted, recovery));
        JdbcKnowledgeFileStore files = new JdbcKnowledgeFileStore(db, properties);
        String path = "skills/test/SKILL.md";
        io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope scope =
                io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope.agent(p.id);
        files.writeFile(scope, path, "original", "text/markdown");
        LearningChange change = new LearningChange();
        change.id = "change";
        change.agentId = p.id;
        change.path = path;
        change.beforeContent = "original";
        change.afterContent = "updated";
        long revision = p.revision;
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.saveLearning(
                                p,
                                change,
                                () -> {
                                    files.writeFile(scope, path, "partial", "text/markdown");
                                    throw new IllegalStateException("Failed write");
                                }));
        assertEquals(revision, store.profile(p.id).revision);
        assertEquals("original", files.readFile(scope, path).getContent());
        assertNull(store.change(p.id, change.id));
        assertTrue(
                store.saveLearning(
                        p, change, () -> files.writeFile(scope, path, "updated", "text/markdown")));
        assertEquals("updated", files.readFile(scope, path).getContent());
        assertEquals(change.id, restarted.latestChange(p.id, path).id);
        assertEquals(p.revision, store.change(p.id, change.id).revision);
        assertFalse(
                store.saveLearning(
                        stale, change, () -> fail("Stale writer must never edit knowledge")));
        JdbcMessageRepository messages =
                new JdbcMessageRepository(db, new ObjectMapper(), properties);
        io.github.differentialmanifold.jagentharness.core.message.AgentMessage evidence =
                io.github.differentialmanifold.jagentharness.core.message.AgentMessage.user(
                        "search", "显存不足100%_ fixed");
        evidence.setRunId("run");
        evidence.setTurnId("turn");
        messages.append(evidence);
        assertEquals(
                1,
                messages.search(java.util.Collections.singletonList("search"), "显存不足100%_", 10)
                        .size());
        assertTrue(
                messages.search(java.util.Collections.singletonList("other"), "显存不足", 10)
                        .isEmpty());
        properties.setApplicationId("two");
        JdbcPersonalAgentStore other =
                new JdbcPersonalAgentStore(db, new ObjectMapper(), properties);
        assertNull(other.profile(p.id));
        assertNull(other.change(p.id, change.id));
        assertTrue(
                new JdbcMessageRepository(db, new ObjectMapper(), properties)
                        .search(java.util.Collections.singletonList("search"), "显存不足", 10)
                        .isEmpty());
        assertNull(other.message(m.id));
        assertNull(other.channel(p.id));
    }
}

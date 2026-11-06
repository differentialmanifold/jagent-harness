package io.github.differentialmanifold.jagentharness.core.personal;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.fs.*;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifestStore;
import io.github.differentialmanifold.jagentharness.core.provider.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.spring.web.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:personal-knowledge;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "harness.personal.learning.enabled=false",
            "harness.store.jdbc.application-id=personal-knowledge",
            "harness.protocol.token=test-token"
        })
class PersonalAgentKnowledgeTest {
    @Autowired PersonalAgentService service;
    @Autowired PersonalAgentKnowledge knowledge;
    @Autowired PersonalAgentStore store;
    @Autowired KnowledgeFileStore files;
    @Autowired SkillManifestStore manifests;
    @Autowired SessionStore history;
    @Autowired SessionManager sessions;
    @Autowired ObjectMapper json;

    @Autowired
    io.github.differentialmanifold.jagentharness.core.conversation.CompactionStore compactions;

    AgentMessage user(String session, String text) {
        AgentMessage m = AgentMessage.user(session, text);
        m.setRunId("test-run");
        m.setTurnId("test-turn");
        return m;
    }

    Profile profile() {
        Profile p = new Profile();
        p.name = "Assistant";
        return service.save(p);
    }

    ObjectNode edit(Profile p) {
        return json.createObjectNode()
                .put("revision", p.revision)
                .put("reason", "Verified evidence");
    }

    String skill(String description) {
        return "---\nname: training\ndescription: "
                + description
                + "\n---\n\n## Procedure\nValidate inputs.\n\n## Verification\nCheck results.\n";
    }

    @Test
    void memoryEditCanRemoveAnExactSpanAndUndoIt() {
        Profile p = profile();
        p.memory = "[fact] An outdated fact.";
        p = service.save(p);
        Conversation c = service.newConversation(p.id, "Update memory");
        String before = p.memory;
        p =
                knowledge.edit(
                        p.id,
                        "memory.edit",
                        edit(p).put("oldText", before).put("content", ""),
                        c.sessionId,
                        "AGENT");
        assertEquals("", p.memory);
        assertEquals(
                before,
                knowledge.undo(p.id, store.latestChange(p.id, "memory").id, p.revision).memory);
    }

    @Test
    void memoryEditsPreserveOtherFactsRejectAmbiguityAndEnforceBudget() {
        Profile p = profile();
        p =
                knowledge.edit(
                        p.id,
                        "memory.add",
                        edit(p).put("category", "preference").put("content", "Use small trials."),
                        null,
                        "USER");
        long stale = p.revision;
        p =
                knowledge.edit(
                        p.id,
                        "memory.add",
                        edit(p).put("category", "fact").put("content", "Trials use SQLite."),
                        null,
                        "USER");
        final Profile current = p;
        assertThrows(
                PersonalAgentException.class,
                () ->
                        knowledge.edit(
                                current.id,
                                "memory.patch",
                                edit(current)
                                        .put("revision", stale)
                                        .put("oldText", "small")
                                        .put("content", "tiny"),
                                null,
                                "USER"));
        p =
                knowledge.edit(
                        p.id,
                        "memory.patch",
                        edit(p).put("oldText", "small").put("content", "tiny"),
                        null,
                        "USER");
        assertTrue(p.memory.contains("tiny trials"));
        assertTrue(p.memory.contains("Trials use SQLite"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PersonalAgentKnowledge.replaceUnique("x x", "x", "y"));
        String before = p.memory;
        Profile full = store.profile(p.id);
        full.memory = String.join("", Collections.nCopies(24001, "x"));
        assertThrows(IllegalArgumentException.class, () -> service.save(full));
        assertEquals(before, store.profile(p.id).memory);
        Profile original = p;
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        knowledge.edit(
                                original.id,
                                "memory.add",
                                edit(original)
                                        .put("category", "fact")
                                        .put(
                                                "content",
                                                String.join("", Collections.nCopies(24000, "x"))),
                                null,
                                "USER"));
        assertEquals(before, store.profile(p.id).memory);
    }

    @Test
    void userEditsAndUndoAreAuditedAndCannotOverwriteNewerLearning() {
        Profile p = profile();
        p.memory = "Keep the evidence.";
        service.save(p);
        LearningChange first = store.latestChange(p.id, "memory");
        assertEquals("USER", first.origin);
        p.memory = "Keep reproducible evidence.";
        service.save(p);
        final Profile current = p;
        assertThrows(
                PersonalAgentException.class,
                () -> knowledge.undo(current.id, first.id, current.revision));
        LearningChange second = store.latestChange(p.id, "memory");
        Profile undone = knowledge.undo(p.id, second.id, p.revision);
        assertEquals("Keep the evidence.", undone.memory);
        LearningChange reversal = store.latestChange(p.id, "memory");
        assertEquals(second.id, reversal.revertsId);
        assertEquals(
                "Keep reproducible evidence.",
                knowledge.undo(p.id, reversal.id, undone.revision).memory);
        Profile clear = store.profile(p.id);
        clear.memory = "";
        service.save(clear);
        assertEquals("", store.profile(p.id).memory);
    }

    @Test
    void skillsStayPrivateAndRemovalCanBeReversedWithManifestRestored() {
        Profile p = profile(), other = profile();
        String path = "skills/training/SKILL.md";
        files.writeFile(
                KnowledgeScope.global(), path, skill("Global instructions"), "text/markdown");
        Conversation c = service.newConversation(p.id, "Evidence");
        AgentMessage evidence = user(c.sessionId, "Small trials succeeded");
        history.appendMessage(evidence);
        p =
                knowledge.edit(
                        p.id,
                        "skills.save",
                        edit(p).put("path", path)
                                .put("content", skill("Plan training"))
                                .put("messageId", evidence.getMessageId()),
                        c.sessionId,
                        "AGENT");
        assertNull(files.readFile(KnowledgeScope.agent(other.id), path));
        assertTrue(
                files.readFile(KnowledgeScope.global(), path)
                        .getContent()
                        .contains("Global instructions"));
        assertTrue(
                manifests.listManifests(KnowledgeScope.agent(p.id)).stream()
                        .anyMatch(m -> "training".equals(m.getName())));
        LearningChange authored = store.latestChange(p.id, path);
        assertEquals(c.sessionId, authored.sessionId);
        assertEquals(evidence.getMessageId(), authored.messageId);
        p =
                knowledge.edit(
                        p.id,
                        "skills.patch",
                        edit(p).put("path", path)
                                .put("oldText", "Validate inputs.")
                                .put("content", "Validate small batches."),
                        c.sessionId,
                        "AGENT");
        files.writeFile(
                KnowledgeScope.agent(p.id),
                "skills/training/references/check.md",
                "Evidence",
                "text/markdown");
        p = knowledge.edit(p.id, "skills.remove", edit(p).put("path", path), c.sessionId, "AGENT");
        assertNull(files.readFile(KnowledgeScope.agent(p.id), path));
        assertEquals(
                "Evidence",
                files.readFile(KnowledgeScope.agent(p.id), "skills/training/references/check.md")
                        .getContent());
        assertFalse(
                manifests.listManifests(KnowledgeScope.agent(p.id)).stream()
                        .anyMatch(m -> "training".equals(m.getName())));
        p = knowledge.undo(p.id, store.latestChange(p.id, path).id, p.revision);
        assertTrue(
                files.readFile(KnowledgeScope.agent(p.id), path)
                        .getContent()
                        .contains("Validate small batches"));
        assertTrue(
                manifests.listManifests(KnowledgeScope.agent(p.id)).stream()
                        .anyMatch(m -> "training".equals(m.getName())));
    }

    @Test
    void validationRejectsUnsafePathsAndForeignPrivateEvidence() {
        Profile p = profile(), other = profile();
        Conversation foreign = service.newConversation(other.id, "Private");
        assertThrows(
                PersonalAgentException.class,
                () ->
                        knowledge.edit(
                                p.id,
                                "memory.add",
                                edit(p).put("category", "fact").put("content", "Do not copy"),
                                foreign.sessionId,
                                "AGENT"));
        for (String path :
                Arrays.asList(
                        "skills/../outside.md", "skills/x/../../outside", "/skills/x/SKILL.md"))
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            knowledge.edit(
                                    p.id,
                                    "skills.save",
                                    edit(p).put("path", path).put("content", skill("Bad")),
                                    null,
                                    "USER"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        knowledge.edit(
                                p.id,
                                "skills.save",
                                edit(p).put("path", "skills/wrong/SKILL.md")
                                        .put("content", skill("Mismatch")),
                                null,
                                "USER"));
        assertThrows(PersonalAgentException.class, () -> knowledge.change(other.id, "missing"));
    }

    @Test
    void historySearchSupportsChineseLiteralWildcardsAndBoundedContextWithoutLeakingAgents() {
        Profile p = profile(), other = profile();
        Conversation own = service.newConversation(p.id, "Own"),
                foreign = service.newConversation(other.id, "Private");
        SessionRecord project = sessions.createSession("Training", null, "Experiment", null, p.id);
        SessionRecord userProject = sessions.createSession("User project", null, "User");
        SessionRecord otherProject =
                sessions.createSession("Other project", null, "Other", null, other.id);
        String phrase = "显存不足100%_";
        history.appendMessage(user(userProject.getSessionId(), phrase + " user secret"));
        history.appendMessage(user(otherProject.getSessionId(), phrase + " other project secret"));
        assertThrows(
                PersonalAgentException.class,
                () -> knowledge.around(p.id, userProject.getSessionId(), "any"));
        assertThrows(
                PersonalAgentException.class,
                () -> knowledge.search(p.id, phrase, "all", otherProject.getSessionId(), 10));
        AgentMessage target = user(own.sessionId, phrase + " fixed");
        history.appendMessage(target);
        history.appendMessage(user(foreign.sessionId, phrase + " secret"));
        history.appendMessage(user(project.getSessionId(), phrase + " verified"));
        history.appendMessage(user(project.getSessionId(), "显存不足100xx wildcard must not match"));
        List<Map<String, Object>> all = knowledge.search(p.id, phrase, "all", null, 10);
        assertEquals(2, all.size());
        assertEquals(1, knowledge.search(p.id, phrase, "personal", null, 10).size());
        assertEquals(1, knowledge.search(p.id, phrase, "projects", null, 10).size());
        assertThrows(
                PersonalAgentException.class,
                () -> knowledge.search(p.id, phrase, "all", foreign.sessionId, 10));
        assertThrows(
                PersonalAgentException.class,
                () -> knowledge.around(p.id, foreign.sessionId, target.getMessageId()));
        assertEquals(
                target.getMessageId(),
                knowledge
                        .around(p.id, own.sessionId, target.getMessageId())
                        .get(0)
                        .get("messageId"));
    }

    PersonalAgentLearning reviewer(ModelProvider provider) {
        AgentSettings settings = new AgentSettings();
        settings.setProvider(provider.getName());
        settings.setModel("test");
        return new PersonalAgentLearning(
                store,
                knowledge,
                history,
                files,
                new ModelProviderRegistry(Collections.singletonList(provider)),
                settings,
                json,
                true,
                1000,
                compactions);
    }

    Inbox snapshot(Profile p, Conversation c, AgentMessage evidence) {
        Inbox review = new Inbox();
        review.id = "review_test";
        review.agentId = p.id;
        review.sessionId = c.sessionId;
        ObjectNode data = json.createObjectNode();
        data.putArray("messages")
                .addObject()
                .put("sessionId", c.sessionId)
                .put("messageId", evidence.getMessageId())
                .put("role", evidence.getRole())
                .put("content", evidence.getContent());
        review.text = data.toString();
        return review;
    }

    @Test
    void compactionCheckpointCapturesTheDiscardedRangeAndIsDeduplicated() throws Exception {
        Profile p = profile();
        Conversation c = service.newConversation(p.id, "Compaction");
        AgentMessage old = user(c.sessionId, "already compacted");
        history.appendMessage(old);
        AgentMessage pending = user(c.sessionId, "preserve this verified lesson");
        history.appendMessage(pending);
        AgentMessage recent = user(c.sessionId, "keep in active context");
        history.appendMessage(recent);
        compactions.save(c.sessionId, "old summary", old.getMessageId());
        PersonalAgentLearning review =
                reviewer(
                        new ModelProvider() {
                            public String getName() {
                                return "checkpoint";
                            }

                            public ModelResponse chat(ModelRequest request) {
                                fail("Checkpoint must not call the model");
                                return null;
                            }
                        });
        Inbox turn = new Inbox();
        turn.id = "checkpoint_" + p.id;
        turn.agentId = p.id;
        turn.sessionId = c.sessionId;
        io.github.differentialmanifold.jagentharness.core.event.AgentEvent event =
                io.github.differentialmanifold.jagentharness.core.event.AgentEvent.of(
                        c.sessionId,
                        "run",
                        "turn",
                        "compaction_start",
                        "{\"compactMessageCount\":1}");
        review.checkpoint(turn, event);
        review.checkpoint(turn, event);
        Inbox captured = store.message("review_" + turn.id);
        assertTrue(captured.text.contains(pending.getMessageId()));
        assertFalse(captured.text.contains(old.getMessageId()));
        assertFalse(captured.text.contains(recent.getMessageId()));
        assertEquals(
                1,
                store.messages(c.sessionId).stream()
                        .filter(m -> "LEARNING".equals(m.source))
                        .count());
    }

    @Test
    void reviewUsesOneToolFreeCallCitesEvidenceAndDoesNotWriteConversationMessages()
            throws Exception {
        Profile p = profile();
        Conversation c = service.newConversation(p.id, "Learn");
        AgentMessage evidence = user(c.sessionId, "I prefer small training trials.");
        history.appendMessage(evidence);
        AtomicInteger calls = new AtomicInteger();
        PersonalAgentLearning review =
                reviewer(
                        new ModelProvider() {
                            public String getName() {
                                return "review-test";
                            }

                            public ModelResponse chat(ModelRequest request) {
                                calls.incrementAndGet();
                                assertTrue(request.getTools().isEmpty());
                                ModelResponse r = new ModelResponse();
                                ObjectNode result = json.createObjectNode();
                                result.putArray("operations")
                                        .addObject()
                                        .put("action", "memory.add")
                                        .put("category", "preference")
                                        .put("content", "Use small training trials.")
                                        .put("reason", "Explicit user preference")
                                        .put("sessionId", c.sessionId)
                                        .put("messageId", evidence.getMessageId());
                                r.setContent(result.toString());
                                return r;
                            }
                        });
        assertTrue(review.review(snapshot(p, c, evidence), StopSignal.none()).startsWith("Saved:"));
        assertEquals(1, calls.get());
        assertEquals(1, history.findMessages(c.sessionId).size());
        assertTrue(store.profile(p.id).memory.contains("small training trials"));
        assertEquals("REVIEW", store.latestChange(p.id, "memory").origin);
    }

    @Test
    void changedKnowledgeSkipsQueuedReviewsAndEmptyDecisionsWriteNothing() throws Exception {
        Profile p = profile();
        Conversation c = service.newConversation(p.id, "No-op");
        AgentMessage evidence = user(c.sessionId, "Hello");
        history.appendMessage(evidence);
        AtomicInteger calls = new AtomicInteger();
        PersonalAgentLearning review =
                reviewer(
                        new ModelProvider() {
                            public String getName() {
                                return "no-op";
                            }

                            public ModelResponse chat(ModelRequest request) {
                                calls.incrementAndGet();
                                ModelResponse r = new ModelResponse();
                                r.setContent("{\"operations\":[]}");
                                return r;
                            }
                        });
        Inbox snapshot = snapshot(p, c, evidence);
        snapshot.knowledgeRevision = p.revision;
        assertEquals("No durable change needed.", review.review(snapshot, StopSignal.none()));
        assertTrue(knowledge.changes(p.id, 0).isEmpty());
        p.memory = "A newer preference.";
        service.save(p);
        assertTrue(review.review(snapshot, StopSignal.none()).contains("skipped stale"));
        assertEquals(1, calls.get());
    }

    @Test
    void skillResourcesPreserveWhitespaceAndManifestsRequireExplicitMetadata() {
        Profile p = profile();
        String path = "skills/training/references/sample.txt",
                content = "  preserved indentation\nlast line\n";
        p =
                knowledge.edit(
                        p.id,
                        "skills.save",
                        edit(p).put("path", path).put("content", content),
                        null,
                        "USER");
        assertEquals(content, files.readFile(KnowledgeScope.agent(p.id), path).getContent());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PersonalAgentKnowledge.validateSkill(
                                "skills/training/SKILL.md",
                                "---\nname: training\n---\n# training\nFallback description"));
        assertThrows(
                RuntimeException.class,
                () ->
                        PersonalAgentKnowledge.validateSkill(
                                "skills/training/SKILL.md",
                                "---\nname: training\ndescription: !!java.net.URL [https://example.com]\n---\nBody"));
    }

    @Test
    void reviewRejectsFabricatedEvidenceDestructiveActionsAndConcurrentEdits() throws Exception {
        Profile p = profile();
        Conversation c = service.newConversation(p.id, "Review limits");
        AgentMessage evidence = user(c.sessionId, "Keep preferences.");
        history.appendMessage(evidence);
        for (String action : Arrays.asList("projects.send", "memory.remove", "memory.add")) {
            PersonalAgentLearning review =
                    reviewer(
                            new ModelProvider() {
                                public String getName() {
                                    return "refuse";
                                }

                                public ModelResponse chat(ModelRequest request) {
                                    ModelResponse r = new ModelResponse();
                                    r.setContent(
                                            "{\"operations\":[{\"action\":\""
                                                    + action
                                                    + "\",\"sessionId\":\"fake\",\"messageId\":\"fake\"}]}");
                                    return r;
                                }
                            });
            assertThrows(
                    IllegalArgumentException.class,
                    () -> review.review(snapshot(p, c, evidence), StopSignal.none()));
        }
        assertTrue(store.profile(p.id).memory.isEmpty());
        PersonalAgentLearning review =
                reviewer(
                        new ModelProvider() {
                            public String getName() {
                                return "concurrent";
                            }

                            public ModelResponse chat(ModelRequest request) {
                                Profile live = store.profile(p.id);
                                live.memory = "User changed this while reviewing.";
                                service.save(live);
                                ModelResponse r = new ModelResponse();
                                ObjectNode result = json.createObjectNode();
                                result.putArray("operations")
                                        .addObject()
                                        .put("action", "memory.add")
                                        .put("category", "fact")
                                        .put("content", "Stale lesson")
                                        .put("reason", "Old evidence")
                                        .put("sessionId", c.sessionId)
                                        .put("messageId", evidence.getMessageId());
                                r.setContent(result.toString());
                                return r;
                            }
                        });
        assertThrows(
                PersonalAgentException.class,
                () -> review.review(snapshot(p, c, evidence), StopSignal.none()));
        assertEquals("User changed this while reviewing.", store.profile(p.id).memory);
    }
}

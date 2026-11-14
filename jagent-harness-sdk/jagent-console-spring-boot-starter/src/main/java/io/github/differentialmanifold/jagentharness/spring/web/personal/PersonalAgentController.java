package io.github.differentialmanifold.jagentharness.spring.web.personal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.differentialmanifold.jagentharness.core.personal.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/personal")
public class PersonalAgentController {
    private final PersonalAgentService service;
    private final PersonalAgentStore store;
    private final PersonalAgentKnowledge knowledge;
    private final PersonalAgentLearning learning;

    public PersonalAgentController(
            PersonalAgentService service,
            PersonalAgentStore store,
            PersonalAgentKnowledge knowledge,
            PersonalAgentLearning learning) {
        this.service = service;
        this.store = store;
        this.knowledge = knowledge;
        this.learning = learning;
    }

    @GetMapping("/agents")
    public List<Profile> agents() {
        return store.profiles();
    }

    @PostMapping("/agents")
    public Profile save(@RequestBody Profile profile) {
        if (profile.id == null && (profile.runnerId == null || profile.runnerId.trim().isEmpty()))
            profile.runnerId = service.defaults().get("primaryRunnerId");
        return service.save(profile);
    }

    @GetMapping("/defaults")
    public Map<String, String> defaults() {
        return service.defaults();
    }

    @PostMapping("/defaults")
    public Map<String, String> defaults(@RequestBody Map<String, String> body) {
        return service.selectPrimaryAgent(
                body.get("primaryAgentId"), body.get("expectedPrimaryAgentId"));
    }

    @GetMapping("/agents/{agent}/conversations")
    public List<Conversation> conversations(@PathVariable String agent) {
        service.require(agent);
        List<Conversation> conversations = store.conversations(agent);
        for (Conversation c : conversations)
            c.running =
                    store.messages(c.sessionId).stream().anyMatch(m -> "RUNNING".equals(m.status));
        return conversations;
    }

    @PostMapping("/agents/{agent}/conversations")
    public Conversation create(@PathVariable String agent, @RequestBody Map<String, String> body) {
        return service.newConversation(agent, body.get("title"));
    }

    @GetMapping("/agents/{agent}/conversations/{session}")
    public Map<String, Object> detail(@PathVariable String agent, @PathVariable String session) {
        service.conversation(agent, session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(
                "messages",
                store.messages(session).stream()
                        .filter(m -> !"LEARNING".equals(m.source))
                        .peek(m -> m.runId = PersonalAgentService.messageRunId(m))
                        .collect(Collectors.toList()));
        out.put("history", service.history(agent, session));
        return out;
    }

    @PostMapping("/agents/{agent}/conversations/{session}")
    public Inbox send(
            @PathVariable String agent,
            @PathVariable String session,
            @RequestBody AgentInput body) {
        return service.send(agent, session, body.text, body.messageId, body.modelId, body.images);
    }

    public static class AgentInput {
        public String text, messageId, modelId;
        public List<io.github.differentialmanifold.jagentharness.core.message.MessageImage> images =
                new ArrayList<>();
    }

    @GetMapping("/agents/{agent}/conversations/{session}/status")
    public Map<String, Object> status(
            @PathVariable String agent,
            @PathVariable String session,
            @RequestParam(required = false) String messageId) {
        return service.status(agent, session, messageId);
    }

    @GetMapping("/agents/{agent}/conversations/{session}/history")
    public Map<String, Object> history(
            @PathVariable String agent,
            @PathVariable String session,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "40") int limit) {
        return service.historyPage(agent, session, after, limit);
    }

    @PostMapping("/agents/{agent}/conversations/{session}/rename")
    public Conversation rename(
            @PathVariable String agent,
            @PathVariable String session,
            @RequestBody Map<String, String> body) {
        return service.renameConversation(agent, session, body.get("title"));
    }

    @PostMapping("/agents/{agent}/conversations/{session}/delete")
    public void delete(@PathVariable String agent, @PathVariable String session) {
        service.deleteConversation(agent, session);
    }

    @org.springframework.beans.factory.annotation.Autowired private PersonalAgentTools agentTools;

    @GetMapping("/agents/{agent}/tools")
    public List<Map<String, Object>> tools(@PathVariable String agent) {
        return agentTools.catalog(service.require(agent));
    }

    @GetMapping("/agents/{agent}/projects")
    public List<io.github.differentialmanifold.jagentharness.core.session.SessionRecord> projects(
            @PathVariable String agent) {
        return service.projectSessions(agent);
    }

    @GetMapping("/agents/{agent}/cards")
    public List<Card> cards(@PathVariable String agent) {
        return service.cards(agent);
    }

    @GetMapping("/agents/{agent}/cards/{task}/executions")
    public List<Inbox> executions(@PathVariable String agent, @PathVariable String task) {
        return service.taskExecutions(agent, task);
    }

    @GetMapping("/agents/{agent}/learning")
    public List<Map<String, Object>> changes(
            @PathVariable String agent, @RequestParam(defaultValue = "0") long beforeRevision) {
        return knowledge.changes(agent, beforeRevision);
    }

    @GetMapping("/agents/{agent}/learning/{change}")
    public LearningChange change(@PathVariable String agent, @PathVariable String change) {
        return knowledge.change(agent, change);
    }

    @PostMapping("/agents/{agent}/learning/{change}/undo")
    public Profile undo(
            @PathVariable String agent, @PathVariable String change, @RequestBody JsonNode body) {
        return knowledge.undo(agent, change, body.path("revision").asLong(-1));
    }

    @GetMapping("/agents/{agent}/reviews")
    public List<Map<String, Object>> reviews(@PathVariable String agent) {
        service.require(agent);
        List<Inbox> reviews = new ArrayList<>();
        for (Conversation c : store.conversations(agent))
            for (Inbox m : store.messages(c.sessionId))
                if ("LEARNING".equals(m.source)) reviews.add(m);
        return reviews.stream()
                .sorted(Comparator.comparingLong((Inbox m) -> m.createdAt).reversed())
                .limit(10)
                .map(
                        m -> {
                            Map<String, Object> out = new LinkedHashMap<>();
                            out.put("id", m.id);
                            out.put("sessionId", m.sessionId);
                            out.put("trigger", m.senderId);
                            out.put("status", m.status);
                            out.put("answer", m.answer);
                            out.put("error", m.error);
                            out.put("createdAt", m.createdAt);
                            return out;
                        })
                .collect(Collectors.toList());
    }

    @PostMapping("/agents/{agent}/reviews")
    public Map<String, Object> review(
            @PathVariable String agent, @RequestBody Map<String, String> body) {
        synchronized (service) {
            service.conversation(agent, body.get("sessionId"));
            Inbox queued =
                    learning.enqueue(agent, body.get("sessionId"), "USER_REQUEST", null, null);
            if (queued == null)
                throw new IllegalArgumentException("Learning is disabled or this Agent is paused");
            return Collections.singletonMap("id", queued.id);
        }
    }

    @GetMapping("/agents/{agent}/skills")
    public List<Map<String, Object>> skills(@PathVariable String agent) {
        return knowledge.availableSkills(agent);
    }

    @GetMapping("/agents/{agent}/skill")
    public Map<String, Object> skill(@PathVariable String agent, @RequestParam String path) {
        return knowledge.availableSkill(agent, path);
    }

    @PostMapping("/agents/{agent}/knowledge")
    public Profile edit(@PathVariable String agent, @RequestBody JsonNode body) {
        return knowledge.edit(agent, body.path("action").asText(), body, null, "USER");
    }

    @GetMapping("/agents/{agent}/history/search")
    public List<Map<String, Object>> search(
            @PathVariable String agent,
            @RequestParam String query,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String sessionId) {
        return knowledge.search(agent, query, scope, sessionId, 20);
    }

    @GetMapping("/agents/{agent}/history/around")
    public List<Map<String, Object>> around(
            @PathVariable String agent,
            @RequestParam String sessionId,
            @RequestParam String messageId) {
        return knowledge.around(agent, sessionId, messageId);
    }
}

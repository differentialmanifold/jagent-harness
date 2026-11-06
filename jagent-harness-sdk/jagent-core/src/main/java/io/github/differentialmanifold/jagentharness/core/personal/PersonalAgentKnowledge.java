package io.github.differentialmanifold.jagentharness.core.personal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.differentialmanifold.jagentharness.core.fs.*;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.prompt.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import java.util.*;
import java.util.stream.Collectors;

/** Personal knowledge uses the existing profile, VFS and conversation history. */
public class PersonalAgentKnowledge {
    public static final int MEMORY_LIMIT = 24000;
    public static final int FILE_LIMIT = 40000;
    private final PersonalAgentStore store;
    private final KnowledgeFileStore files;
    private final MessageRepository messages;
    private final SessionRepository sessions;

    public PersonalAgentKnowledge(
            PersonalAgentStore store,
            KnowledgeFileStore files,
            MessageRepository messages,
            SessionRepository sessions) {
        this.store = store;
        this.files = files;
        this.messages = messages;
        this.sessions = sessions;
    }

    public Profile require(String agent) {
        Profile p = store.profile(agent);
        if (p == null)
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.NOT_FOUND, "AGENT_NOT_FOUND", "Agent not found");
        return p;
    }

    static String limited(String content, int max, String label) {
        String value = content == null ? "" : content;
        if (value.length() > max)
            throw new IllegalArgumentException(
                    label
                            + " exceeds "
                            + max
                            + " characters ("
                            + value.length()
                            + "). Consolidate or shorten it; nothing was saved.");
        return value;
    }

    public Profile saveProfile(Profile p) {
        p.memory = limited(p.memory, MEMORY_LIMIT, "Memory");
        Profile old = p.id == null ? null : store.profile(p.id);
        if (old != null && !Objects.equals(old.memory, p.memory)) {
            commit(
                    p,
                    "memory",
                    old.memory,
                    p.memory,
                    "Edited in settings",
                    "USER",
                    null,
                    null,
                    null);
        } else if (!store.save(p)) throw PersonalAgentSupport.conflict();
        return p;
    }

    public Map<String, Object> memory(String agent) {
        Profile p = require(agent);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("memory", p.memory);
        out.put("revision", p.revision);
        out.put("characters", p.memory.length());
        out.put("limit", MEMORY_LIMIT);
        return out;
    }

    public Profile edit(String agent, String action, JsonNode body, String session, String origin) {
        Profile p = require(agent);
        if (p.revision != body.path("revision").asLong(-1)) throw PersonalAgentSupport.conflict();
        if (session != null) requireAccessible(agent, session);
        String messageId = body.path("messageId").asText(null);
        if (messageId != null
                && (session == null
                        || messages.findBySessionId(session).stream()
                                .noneMatch(m -> messageId.equals(m.getMessageId()))))
            throw new IllegalArgumentException(
                    "Evidence message is not in the source conversation");
        String reason = required(body, "reason", 512);
        String before, after, path;
        if (action.startsWith("memory.")) {
            path = "memory";
            before = p.memory;
            if ("memory.add".equals(action)) {
                String category = required(body, "category", 32);
                if (!Arrays.asList("preference", "fact", "lesson").contains(category))
                    throw new IllegalArgumentException("Use preference, fact, or lesson");
                String entry = "[" + category + "] " + exact(body, "content", MEMORY_LIMIT);
                if (Arrays.asList(before.split("\\n\\n")).contains(entry)) return p;
                after = before.isEmpty() ? entry : before + "\n\n" + entry;
            } else if ("memory.patch".equals(action)
                    || "memory.remove".equals(action)
                    || "memory.edit".equals(action)) {
                if ("memory.edit".equals(action) && !body.path("content").isTextual())
                    throw new IllegalArgumentException(
                            "content must be a string; use an empty string to remove the span");
                after =
                        replaceUnique(
                                        before,
                                        exact(body, "oldText", MEMORY_LIMIT),
                                        "memory.remove".equals(action)
                                                ? ""
                                                : "memory.edit".equals(action)
                                                        ? limited(
                                                                body.get("content").asText(),
                                                                MEMORY_LIMIT,
                                                                "Memory")
                                                        : exact(body, "content", MEMORY_LIMIT))
                                .trim();
            } else throw new IllegalArgumentException("Unknown memory action");
            after = limited(after, MEMORY_LIMIT, "Memory");
            p.memory = after;
        } else {
            path = skillPath(required(body, "path", 512));
            before = content(agent, path);
            if ("skills.remove".equals(action)) {
                if (!path.endsWith("/SKILL.md") || before == null)
                    throw new IllegalArgumentException(
                            "Remove an existing SKILL.md; its resources are retained for undo");
                after = null;
            } else if ("skills.patch".equals(action)) {
                if (before == null)
                    throw new IllegalArgumentException("Read an existing skill before patching it");
                after =
                        replaceUnique(
                                before,
                                exact(body, "oldText", FILE_LIMIT),
                                exact(body, "content", FILE_LIMIT));
            } else if ("skills.save".equals(action)) {
                after = exact(body, "content", FILE_LIMIT);
            } else throw new IllegalArgumentException("Unknown skill action");
            if (after != null) validateSkill(path, after);
        }
        if (!Objects.equals(before, after))
            commit(p, path, before, after, reason, origin, session, messageId, null);
        return p;
    }

    static String replaceUnique(String text, String old, String replacement) {
        int pos = text.indexOf(old);
        if (pos < 0 || text.indexOf(old, pos + 1) >= 0)
            throw new IllegalArgumentException(
                    "oldText must match exactly once. Read the latest content and include more context.");
        return text.substring(0, pos) + replacement + text.substring(pos + old.length());
    }

    static String skillPath(String raw) {
        String path = KnowledgeFilePaths.normalize(raw);
        if (!path.equals(raw)
                || !path.matches(
                        "skills/[a-z0-9][a-z0-9-]{0,62}/(SKILL\\.md|(references|scripts|templates|assets)/[a-zA-Z0-9_./-]+)"))
            throw new IllegalArgumentException(
                    "Use skills/<name>/SKILL.md or a file under references, scripts, templates, assets");
        return path;
    }

    static void validateSkill(String path, String content) {
        limited(content, FILE_LIMIT, "Skill file");
        if (path.endsWith("/SKILL.md")) {
            java.util.regex.Matcher front =
                    java.util.regex.Pattern.compile(
                                    "(?s)\\A---\\r?\\n(.*?)\\r?\\n---(?:\\r?\\n|\\z)")
                            .matcher(content);
            if (!front.find())
                throw new IllegalArgumentException(
                        "SKILL.md needs YAML frontmatter with name and description");
            Object parsed =
                    new org.yaml.snakeyaml.Yaml(
                                    new org.yaml.snakeyaml.constructor.SafeConstructor(
                                            new org.yaml.snakeyaml.LoaderOptions()))
                            .load(front.group(1));
            Map<?, ?> metadata =
                    parsed instanceof Map ? (Map<?, ?>) parsed : Collections.emptyMap();
            Object name = metadata.get("name"), description = metadata.get("description");
            if (!path.split("/")[1].equals(name)
                    || !(description instanceof String)
                    || ((String) description).trim().isEmpty()
                    || ((String) description).length() > 1024)
                throw new IllegalArgumentException(
                        "Skill name must match its directory; description must contain 1 to 1024 characters");
        }
    }

    private String content(String agent, String path) {
        if ("memory".equals(path)) return require(agent).memory;
        KnowledgeFile f = files.readFile(KnowledgeScope.agent(agent), path);
        return f == null ? null : f.getContent();
    }

    private void commit(
            Profile p,
            String path,
            String before,
            String after,
            String reason,
            String origin,
            String session,
            String message,
            String reverts) {
        LearningChange c = new LearningChange();
        c.id = Ids.newId("learn");
        c.agentId = p.id;
        c.path = path;
        c.beforeContent = before;
        c.afterContent = after;
        c.reason = reason;
        c.origin = origin;
        c.sessionId = session;
        c.messageId = message;
        c.revertsId = reverts;
        c.createdAt = System.currentTimeMillis();
        if (!store.saveLearning(
                p,
                c,
                () -> {
                    if ("memory".equals(path)) return;
                    if (after == null) files.deleteFile(KnowledgeScope.agent(p.id), path);
                    else files.writeFile(KnowledgeScope.agent(p.id), path, after, "text/plain");
                })) throw PersonalAgentSupport.conflict();
    }

    public Profile undo(String agent, String id, long revision) {
        Profile p = require(agent);
        if (p.revision != revision) throw PersonalAgentSupport.conflict();
        LearningChange c = change(agent, id);
        LearningChange latest = store.latestChange(agent, c.path);
        if (latest == null
                || !id.equals(latest.id)
                || !Objects.equals(content(agent, c.path), c.afterContent))
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.CONFLICT,
                    "LEARNING_CHANGED",
                    "This knowledge has newer edits. Review the latest change before undoing.");
        if ("memory".equals(c.path)) p.memory = limited(c.beforeContent, MEMORY_LIMIT, "Memory");
        commit(
                p,
                c.path,
                c.afterContent,
                c.beforeContent,
                "Undo: " + c.reason,
                "USER",
                c.sessionId,
                c.messageId,
                c.id);
        return p;
    }

    public LearningChange change(String agent, String id) {
        require(agent);
        LearningChange c = store.change(agent, id);
        if (c == null)
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.NOT_FOUND,
                    "CHANGE_NOT_FOUND",
                    "Learning change not found");
        return c;
    }

    public List<Map<String, Object>> changes(String agent, long before) {
        require(agent);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LearningChange c : store.changes(agent, before, 30)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.id);
            row.put("path", c.path);
            row.put("reason", c.reason);
            row.put("origin", c.origin);
            row.put("revision", c.revision);
            row.put("createdAt", c.createdAt);
            row.put("sessionId", c.sessionId);
            row.put("messageId", c.messageId);
            row.put(
                    "canUndo",
                    c.id.equals(store.latestChange(agent, c.path).id)
                            && Objects.equals(content(agent, c.path), c.afterContent));
            out.add(row);
        }
        return out;
    }

    public List<Map<String, Object>> skills(String agent) {
        require(agent);
        return files.listFiles(KnowledgeScope.agent(agent), "skills").stream()
                .map(
                        f -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("path", f.getPath());
                            item.put("updatedAt", String.valueOf(f.getUpdatedAt()));
                            item.put("readOnly", false);
                            return item;
                        })
                .collect(Collectors.toList());
    }

    public Map<String, Object> skill(String agent, String path) {
        Profile p = require(agent);
        path = KnowledgeFilePaths.normalize(path);
        if (!path.startsWith("skills/")) throw new IllegalArgumentException("Use a skill path");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("content", content(agent, path));
        out.put("revision", p.revision);
        return out;
    }

    private SessionRecord requireAccessible(String agent, String session) {
        require(agent);
        SessionRecord record = sessions.findBySessionId(session);
        Conversation c = store.conversation(session);
        if (record == null
                || (record.isStandalone()
                        ? c == null || !agent.equals(c.agentId)
                        : !agent.equals(record.getOwnerAgentId())))
            throw new PersonalAgentException(
                    PersonalAgentException.Kind.NOT_FOUND,
                    "HISTORY_NOT_FOUND",
                    "Conversation not accessible to this Agent");
        return record;
    }

    public List<Map<String, Object>> search(
            String agent, String query, String scope, String session, int limit) {
        require(agent);
        query = PersonalAgentSupport.text(query, "query", 256);
        if (!Arrays.asList("all", "personal", "projects").contains(scope))
            throw new IllegalArgumentException("scope must be all, personal, or projects");
        Set<String> ids = new LinkedHashSet<>();
        if (session != null && !session.isEmpty()) {
            requireAccessible(agent, session);
            ids.add(session);
        } else {
            if (!"projects".equals(scope))
                for (Conversation c : store.conversations(agent)) ids.add(c.sessionId);
            if (!"personal".equals(scope))
                for (SessionRecord s : sessions.findAll())
                    if (!s.isStandalone() && agent.equals(s.getOwnerAgentId()))
                        ids.add(s.getSessionId());
        }
        final String phrase = query;
        return messages.search(ids, query, Math.min(20, Math.max(1, limit))).stream()
                .map(m -> excerpt(m, phrase, 1400))
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> around(String agent, String session, String message) {
        requireAccessible(agent, session);
        List<AgentMessage> all = messages.findBySessionId(session);
        for (int i = 0; i < all.size(); i++)
            if (message.equals(all.get(i).getMessageId()))
                return all.subList(Math.max(0, i - 3), Math.min(all.size(), i + 4)).stream()
                        .map(m -> excerpt(m, "", 4000))
                        .collect(Collectors.toList());
        throw new IllegalArgumentException("Message not found in this conversation");
    }

    private Map<String, Object> excerpt(AgentMessage m, String query, int max) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", m.getSessionId());
        out.put("messageId", m.getMessageId());
        out.put("role", AgentMessage.isRuntimeContext(m) ? "context" : m.getRole());
        out.put("createdAt", String.valueOf(m.getCreatedAt()));
        SessionRecord s = sessions.findBySessionId(m.getSessionId());
        out.put("title", s == null ? "" : s.getTitle());
        out.put("projectId", s == null ? null : s.getProjectId());
        String text = m.getContent() == null ? "" : m.getContent();
        int hit = text.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        int start = Math.max(0, hit - 200), end = Math.min(text.length(), start + max);
        out.put("content", text.substring(start, end));
        out.put("truncated", start > 0 || end < text.length());
        return out;
    }

    private static String exact(JsonNode body, String key, int max) {
        JsonNode node = body.get(key);
        if (node == null || !node.isTextual() || node.asText().trim().isEmpty())
            throw new IllegalArgumentException(key + " must contain text");
        return limited(node.asText(), max, key);
    }

    private static String required(JsonNode body, String key, int max) {
        return PersonalAgentSupport.text(body.path(key).asText(null), key, max);
    }
}

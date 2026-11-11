package io.github.differentialmanifold.jagentharness.store.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.FailedTurn;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcMessageRepository implements MessageRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String applicationId;

    public JdbcMessageRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, JdbcStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.applicationId = properties.requireApplicationId();
    }

    @Override
    public void appendAll(List<AgentMessage> messages) {
        JdbcTransactions.write(jdbcTemplate)
                .execute(
                        status -> {
                            for (AgentMessage message : messages) append(message);
                            return null;
                        });
    }

    @Override
    public void append(AgentMessage message) {
        jdbcTemplate.update(
                "insert into messages "
                        + "(application_id, message_id, session_id, run_id, turn_id, parent_message_id, role, content, "
                        + "images_json, reasoning_content, "
                        + "tool_call_id, tool_name, tool_calls_json, "
                        + "stop_reason, metadata_json, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                applicationId,
                message.getMessageId(),
                message.getSessionId(),
                message.getRunId(),
                message.getTurnId(),
                message.getParentMessageId(),
                message.getRole(),
                message.getContent(),
                writeImages(message.getImages()),
                message.getReasoningContent(),
                message.getToolCallId(),
                message.getToolName(),
                writeToolCalls(message.getToolCalls()),
                message.getStopReason(),
                message.getMetadataJson(),
                JdbcTimeCodec.encode(message.getCreatedAt()));
    }

    @Override
    public List<AgentMessage> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
                "select * from messages where application_id = ? and session_id = ? "
                        + "order by id asc, created_at asc",
                mapper(),
                applicationId,
                sessionId);
    }

    @Override
    public List<AgentMessage> findExecutionBySessionId(String sessionId) {
        return jdbcTemplate.query(
                "select * from messages where application_id = ? and session_id = ? and id >= coalesce("
                        + "(select max(id) from messages where application_id = ? and session_id = ? "
                        + "and role = 'assistant' and (stop_reason is null or stop_reason not in ('failed', 'aborted'))), 0) order by id asc",
                mapper(),
                applicationId,
                sessionId,
                applicationId,
                sessionId);
    }

    @Override
    public List<AgentMessage> findRecentBySessionId(String sessionId, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Message limit must be positive");
        List<AgentMessage> messages =
                jdbcTemplate.query(
                        "select * from messages where application_id = ? and session_id = ? order by id desc limit ?",
                        mapper(),
                        applicationId,
                        sessionId,
                        limit);
        java.util.Collections.reverse(messages);
        return messages;
    }

    @Override
    public List<AgentMessage> findContextBySessionId(String sessionId) {
        Long cursor =
                jdbcTemplate.queryForObject(
                        "select coalesce(max(m.id), 0) from messages m join compaction_states c "
                                + "on m.application_id = c.application_id and m.session_id = c.session_id and m.message_id = c.cursor_message_id "
                                + "where c.application_id = ? and c.session_id = ?",
                        Long.class,
                        applicationId,
                        sessionId);
        List<AgentMessage> history =
                jdbcTemplate.query(
                        "select * from messages where application_id = ? and session_id = ? and id > ? order by id asc",
                        mapper(),
                        applicationId,
                        sessionId,
                        cursor);
        List<AgentMessage> receipts = new ArrayList<>();
        // Older versions stored failures only in the timeline. Recover those boundaries too.
        jdbcTemplate.query(
                "select e.run_id, e.turn_id, e.payload_json from timeline_events e where e.application_id = ? and e.session_id = ? and e.type = 'turn_end' "
                        + "and exists (select 1 from messages m where m.application_id = e.application_id and m.session_id = e.session_id "
                        + "and m.run_id = e.run_id and m.turn_id = e.turn_id and m.id > ?)",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> {
                            try {
                                if (!"failed"
                                        .equals(
                                                objectMapper
                                                        .readTree(rs.getString("payload_json"))
                                                        .path("status")
                                                        .asText())) return;
                            } catch (IOException e) {
                                throw new IllegalStateException("Invalid turn event", e);
                            }
                            String runId = rs.getString("run_id"), turnId = rs.getString("turn_id");
                            if (runId != null && turnId != null)
                                receipts.add(
                                        FailedTurn.receipt(history, sessionId, runId, turnId, ""));
                        },
                applicationId,
                sessionId,
                cursor);
        history.addAll(receipts);
        return FailedTurn.context(history);
    }

    private RowMapper<AgentMessage> mapper() {
        return (rs, rowNum) -> {
            AgentMessage message = new AgentMessage();
            message.setMessageId(rs.getString("message_id"));
            message.setSessionId(rs.getString("session_id"));
            message.setRunId(rs.getString("run_id"));
            message.setTurnId(rs.getString("turn_id"));
            message.setParentMessageId(rs.getString("parent_message_id"));
            message.setRole(rs.getString("role"));
            message.setContent(rs.getString("content"));
            message.setImages(readImages(rs.getString("images_json")));
            message.setReasoningContent(rs.getString("reasoning_content"));
            message.setToolCallId(rs.getString("tool_call_id"));
            message.setToolName(rs.getString("tool_name"));
            message.setToolCalls(readToolCalls(rs.getString("tool_calls_json")));
            message.setStopReason(rs.getString("stop_reason"));
            message.setMetadataJson(rs.getString("metadata_json"));
            message.setCreatedAt(JdbcTimeCodec.decode(rs.getString("created_at")));
            return message;
        };
    }

    @Override
    public List<AgentMessage> search(
            java.util.Collection<String> sessionIds, String query, int limit) {
        if (sessionIds.isEmpty()) return java.util.Collections.emptyList();
        // Fold both operands in Java: SQL lower() has different Unicode behavior on SQLite,
        // H2 and PostgreSQL. Page through text only, retaining at most the requested matches.
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        int maximum = Math.min(50, Math.max(1, limit));
        List<String> ids = new ArrayList<>(sessionIds);
        List<AgentMessage> matches = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += 100) {
            List<String> batch = ids.subList(start, Math.min(start + 100, ids.size()));
            List<Object> args = new ArrayList<>();
            args.add(applicationId);
            args.addAll(batch);
            List<Long> matchingIds = new ArrayList<>();
            long before = Long.MAX_VALUE;
            while (matchingIds.size() < maximum) {
                List<Object> pageArgs = new ArrayList<>(args);
                pageArgs.add(before);
                List<Map<String, Object>> page =
                        jdbcTemplate.query(
                                "select id, content from messages where application_id=? and session_id in ("
                                        + String.join(
                                                ",",
                                                java.util.Collections.nCopies(batch.size(), "?"))
                                        + ") and id < ? order by id desc limit 250",
                                (rs, rowNum) -> {
                                    Map<String, Object> row = new HashMap<>();
                                    row.put("id", rs.getLong("id"));
                                    row.put("content", rs.getString("content"));
                                    return row;
                                },
                                pageArgs.toArray());
                for (Map<String, Object> row : page) {
                    before = ((Number) row.get("id")).longValue();
                    String content = (String) row.get("content");
                    if (content != null
                            && content.toLowerCase(java.util.Locale.ROOT).contains(needle))
                        matchingIds.add(before);
                    if (matchingIds.size() == maximum) break;
                }
                if (page.size() < 250) break;
            }
            if (!matchingIds.isEmpty()) {
                List<Object> matchArgs = new ArrayList<>();
                matchArgs.add(applicationId);
                matchArgs.addAll(matchingIds);
                matches.addAll(
                        jdbcTemplate.query(
                                "select * from messages where application_id=? and id in ("
                                        + String.join(
                                                ",",
                                                java.util.Collections.nCopies(
                                                        matchingIds.size(), "?"))
                                        + ") order by id desc",
                                mapper(),
                                matchArgs.toArray()));
            }
        }

        return matches.stream()
                .sorted(java.util.Comparator.comparing(AgentMessage::getCreatedAt).reversed())
                .limit(Math.min(50, Math.max(1, limit)))
                .collect(java.util.stream.Collectors.toList());
    }

    private String writeToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(toolCalls);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize tool calls", e);
        }
    }

    private List<ToolCall> readToolCalls(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<ToolCall>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ToolCall>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize tool calls", e);
        }
    }

    private String writeImages(List<MessageImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(images);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize message images", e);
        }
    }

    private List<MessageImage> readImages(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<MessageImage>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<MessageImage>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize message images", e);
        }
    }
}

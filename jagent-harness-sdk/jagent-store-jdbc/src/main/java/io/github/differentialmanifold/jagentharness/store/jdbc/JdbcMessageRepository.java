package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcMessageRepository implements MessageRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String applicationId;

    public JdbcMessageRepository(JdbcTemplate jdbcTemplate,
                                 ObjectMapper objectMapper,
                                 JdbcStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.applicationId = properties.requireApplicationId();
    }

    @Override
    public void append(AgentMessage message) {
        jdbcTemplate.update("insert into messages "
                        + "(application_id, message_id, session_id, run_id, turn_id, parent_message_id, role, content, "
                        + "images_json, reasoning_content, "
                        + "tool_call_id, tool_name, tool_calls_json, "
                        + "stop_reason, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            message.setCreatedAt(JdbcTimeCodec.decode(rs.getString("created_at")));
            return message;
        };
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
            return objectMapper.readValue(json, new TypeReference<List<ToolCall>>() {
            });
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
            return objectMapper.readValue(json, new TypeReference<List<MessageImage>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize message images", e);
        }
    }
}

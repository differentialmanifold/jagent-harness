package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.util.List;

import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsage;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsageStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcModelCallUsageStore implements ModelCallUsageStore {

    private final JdbcTemplate jdbcTemplate;
    private final String applicationId;
    private final RowMapper<ModelCallUsage> mapper = (rs, rowNum) -> {
        ModelCallUsage usage = new ModelCallUsage();
        usage.setUsageId(rs.getString("usage_id"));
        usage.setSessionId(rs.getString("session_id"));
        usage.setRunId(rs.getString("run_id"));
        usage.setTurnId(rs.getString("turn_id"));
        usage.setMessageId(rs.getString("message_id"));
        usage.setProvider(rs.getString("provider"));
        usage.setModel(rs.getString("model"));
        usage.setContextWindowTokens(rs.getInt("context_window_tokens"));
        usage.setThresholdTokens(rs.getInt("threshold_tokens"));
        usage.setEstimateSource(rs.getString("estimate_source"));
        usage.setEstimatedTokens(nullableInt(rs, "estimated_tokens"));
        usage.setActualContextTokens(nullableInt(rs, "actual_context_tokens"));
        usage.setPromptTokens(nullableInt(rs, "prompt_tokens"));
        usage.setCompletionTokens(nullableInt(rs, "completion_tokens"));
        usage.setReasoningTokens(nullableInt(rs, "reasoning_tokens"));
        usage.setCachedTokens(nullableInt(rs, "cached_tokens"));
        usage.setTotalTokens(nullableInt(rs, "total_tokens"));
        usage.setCreatedAt(JdbcTimeCodec.decode(rs.getString("created_at")));
        return usage;
    };

    public JdbcModelCallUsageStore(JdbcTemplate jdbcTemplate, JdbcStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.applicationId = properties.requireApplicationId();
    }

    @Override
    public ModelCallUsage findLatestBySessionId(String sessionId) {
        List<ModelCallUsage> usages = jdbcTemplate.query(
                "select * from model_call_usages where application_id = ? and session_id = ? "
                        + "order by id desc limit 1",
                mapper,
                applicationId,
                sessionId);
        return usages.isEmpty() ? null : usages.get(0);
    }

    @Override
    public void append(ModelCallUsage usage) {
        if (usage == null) {
            return;
        }
        jdbcTemplate.update("insert into model_call_usages "
                        + "(application_id, usage_id, session_id, run_id, turn_id, message_id, provider, model, "
                        + "context_window_tokens, threshold_tokens, estimate_source, estimated_tokens, "
                        + "actual_context_tokens, prompt_tokens, completion_tokens, reasoning_tokens, "
                        + "cached_tokens, total_tokens, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                applicationId,
                usage.getUsageId(),
                usage.getSessionId(),
                usage.getRunId(),
                usage.getTurnId(),
                usage.getMessageId(),
                usage.getProvider(),
                usage.getModel(),
                usage.getContextWindowTokens(),
                usage.getThresholdTokens(),
                usage.getEstimateSource(),
                usage.getEstimatedTokens(),
                usage.getActualContextTokens(),
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getReasoningTokens(),
                usage.getCachedTokens(),
                usage.getTotalTokens(),
                JdbcTimeCodec.encode(usage.getCreatedAt()));
    }

    private Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}

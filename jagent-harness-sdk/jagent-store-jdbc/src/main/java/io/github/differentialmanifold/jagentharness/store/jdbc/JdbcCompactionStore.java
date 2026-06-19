package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.time.Instant;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.conversation.CompactionState;
import io.github.differentialmanifold.jagentharness.core.conversation.CompactionStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcCompactionStore implements CompactionStore {

    private final JdbcTemplate jdbcTemplate;
    private final String applicationId;
    private final RowMapper<CompactionState> mapper = (rs, rowNum) -> {
        CompactionState state = new CompactionState();
        state.setSessionId(rs.getString("session_id"));
        state.setSummary(rs.getString("summary"));
        state.setCursorMessageId(rs.getString("cursor_message_id"));
        state.setVersion(rs.getLong("version"));
        state.setMetadataJson(rs.getString("metadata_json"));
        state.setUpdatedAt(JdbcTimeCodec.decode(rs.getString("updated_at")));
        return state;
    };

    public JdbcCompactionStore(JdbcTemplate jdbcTemplate, JdbcStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.applicationId = properties.requireApplicationId();
    }

    @Override
    public CompactionState findBySessionId(String sessionId) {
        List<CompactionState> states = jdbcTemplate.query(
                "select * from compaction_states where application_id = ? and session_id = ?",
                mapper,
                applicationId,
                sessionId);
        return states.isEmpty() ? null : states.get(0);
    }

    @Override
    public void save(String sessionId, String summary, String cursorMessageId) {
        String now = JdbcTimeCodec.encode(Instant.now());
        int updated = jdbcTemplate.update(
                "update compaction_states "
                        + "set summary = ?, cursor_message_id = ?, version = version + 1, updated_at = ? "
                        + "where application_id = ? and session_id = ?",
                summary,
                cursorMessageId,
                now,
                applicationId,
                sessionId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "insert into compaction_states "
                            + "(application_id, session_id, summary, cursor_message_id, version, metadata_json, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?)",
                    applicationId,
                    sessionId,
                    summary,
                    cursorMessageId,
                    1L,
                    null,
                    now);
        }
    }
}

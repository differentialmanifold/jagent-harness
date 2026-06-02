package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.util.List;

import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRepository;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcTimelineEventRepository implements TimelineEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTimelineEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(AgentEvent event) {
        jdbcTemplate.update("insert into timeline_events "
                        + "(event_id, session_id, turn_id, type, payload_json, created_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                event.getEventId(),
                event.getSessionId(),
                event.getTurnId(),
                event.getType(),
                event.getPayloadJson(),
                JdbcTimeCodec.encode(event.getCreatedAt()));
    }

    @Override
    public List<AgentEvent> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
                "select * from timeline_events where session_id = ? "
                        + "order by id asc, created_at asc",
                mapper(),
                sessionId);
    }

    private RowMapper<AgentEvent> mapper() {
        return (rs, rowNum) -> {
            AgentEvent event = new AgentEvent();
            event.setEventId(rs.getString("event_id"));
            event.setSessionId(rs.getString("session_id"));
            event.setTurnId(rs.getString("turn_id"));
            event.setType(rs.getString("type"));
            event.setPayloadJson(rs.getString("payload_json"));
            event.setCreatedAt(JdbcTimeCodec.decode(rs.getString("created_at")));
            return event;
        };
    }
}

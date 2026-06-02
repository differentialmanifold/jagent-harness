package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.time.Instant;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.session.SessionRepository;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcSessionRepository implements SessionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<SessionRecord> mapper = (rs, rowNum) -> {
        SessionRecord session = new SessionRecord();
        session.setSessionId(rs.getString("session_id"));
        session.setTitle(rs.getString("title"));
        session.setWorkspacePath(rs.getString("workspace_path"));
        session.setStatus(rs.getString("status"));
        session.setMetadataJson(rs.getString("metadata_json"));
        session.setCreatedAt(JdbcTimeCodec.decode(rs.getString("created_at")));
        session.setUpdatedAt(JdbcTimeCodec.decode(rs.getString("updated_at")));
        return session;
    };

    public JdbcSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SessionRecord create(String title, String workspacePath) {
        Instant now = Instant.now();
        SessionRecord session = new SessionRecord();
        session.setSessionId(Ids.newId("ses"));
        session.setTitle(title == null || title.trim().isEmpty() ? "New Session" : title.trim());
        session.setWorkspacePath(workspacePath);
        session.setStatus(SessionRecord.STATUS_ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        jdbcTemplate.update("insert into sessions "
                        + "(session_id, title, workspace_path, status, metadata_json, "
                        + "created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                session.getSessionId(),
                session.getTitle(),
                session.getWorkspacePath(),
                session.getStatus(),
                session.getMetadataJson(),
                JdbcTimeCodec.encode(session.getCreatedAt()),
                JdbcTimeCodec.encode(session.getUpdatedAt()));
        return session;
    }

    @Override
    public SessionRecord findBySessionId(String sessionId) {
        List<SessionRecord> sessions = jdbcTemplate.query(
                "select * from sessions where session_id = ? and status <> ?",
                mapper,
                sessionId,
                SessionRecord.STATUS_DELETED);
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    @Override
    public List<SessionRecord> findAll() {
        return jdbcTemplate.query(
                "select * from sessions where status <> ? order by updated_at desc",
                mapper,
                SessionRecord.STATUS_DELETED);
    }

    @Override
    public void touch(String sessionId) {
        jdbcTemplate.update(
                "update sessions set updated_at = ? where session_id = ?",
                JdbcTimeCodec.encode(Instant.now()),
                sessionId);
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        jdbcTemplate.update(
                "update sessions set title = ?, updated_at = ? where session_id = ?",
                title,
                JdbcTimeCodec.encode(Instant.now()),
                sessionId);
    }

    @Override
    public void delete(String sessionId) {
        updateStatus(sessionId, SessionRecord.STATUS_DELETED);
    }

    public void updateStatus(String sessionId, String status) {
        jdbcTemplate.update(
                "update sessions set status = ?, updated_at = ? where session_id = ?",
                status,
                JdbcTimeCodec.encode(Instant.now()),
                sessionId);
    }

}

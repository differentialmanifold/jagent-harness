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
    private final String applicationId;
    private final RowMapper<SessionRecord> mapper = (rs, rowNum) -> {
        SessionRecord session = new SessionRecord();
        session.setSessionId(rs.getString("session_id"));
        session.setTitle(rs.getString("title"));
        session.setProjectId(rs.getString("project_id"));
        session.setProjectName(rs.getString("project_name"));
        session.setWorkspacePath(rs.getString("workspace_path"));
        session.setStatus(rs.getString("status"));
        session.setMetadataJson(rs.getString("metadata_json"));
        session.setCreatedAt(JdbcTimeCodec.decode(rs.getString("created_at")));
        session.setUpdatedAt(JdbcTimeCodec.decode(rs.getString("updated_at")));
        return session;
    };

    public JdbcSessionRepository(JdbcTemplate jdbcTemplate, JdbcStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.applicationId = properties.requireApplicationId();
    }

    @Override
    public SessionRecord create(String title, String workspacePath) {
        return create(title, workspacePath, null);
    }

    @Override
    public SessionRecord create(String title, String workspacePath, String projectName) {
        return create(title, workspacePath, projectName, Ids.newId("project"));
    }

    @Override
    public SessionRecord create(String title, String workspacePath, String projectName, String projectId) {
        Instant now = Instant.now();
        SessionRecord session = new SessionRecord();
        session.setSessionId(Ids.newId("ses"));
        session.setTitle(title == null || title.trim().isEmpty() ? "New Session" : title.trim());
        session.setProjectId(projectId);
        session.setProjectName(projectName == null || projectName.trim().isEmpty() ? null : projectName.trim());
        session.setWorkspacePath(workspacePath);
        session.setStatus(SessionRecord.STATUS_ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        upsertProject(session, now);
        jdbcTemplate.update("insert into sessions "
                        + "(application_id, session_id, title, project_id, project_name, workspace_path, status, metadata_json, "
                        + "created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                applicationId,
                session.getSessionId(),
                session.getTitle(),
                session.getProjectId(),
                session.getProjectName(),
                session.getWorkspacePath(),
                session.getStatus(),
                session.getMetadataJson(),
                JdbcTimeCodec.encode(session.getCreatedAt()),
                JdbcTimeCodec.encode(session.getUpdatedAt()));
        return session;
    }

    private void upsertProject(SessionRecord session, Instant now) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from projects where application_id = ? and project_id = ?",
                Integer.class,
                applicationId,
                session.getProjectId());
        if (count != null && count.intValue() > 0) {
            jdbcTemplate.update(
                    "update projects set name = ?, workspace_path = ?, updated_at = ? "
                            + "where application_id = ? and project_id = ?",
                    session.getProjectName(), session.getWorkspacePath(), JdbcTimeCodec.encode(now),
                    applicationId, session.getProjectId());
            return;
        }
        jdbcTemplate.update(
                "insert into projects (application_id, project_id, name, workspace_path, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                applicationId, session.getProjectId(), session.getProjectName(), session.getWorkspacePath(),
                JdbcTimeCodec.encode(now), JdbcTimeCodec.encode(now));
    }

    @Override
    public SessionRecord findBySessionId(String sessionId) {
        List<SessionRecord> sessions = jdbcTemplate.query(
                "select * from sessions where application_id = ? and session_id = ? and status <> ?",
                mapper,
                applicationId,
                sessionId,
                SessionRecord.STATUS_DELETED);
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    @Override
    public List<SessionRecord> findAll() {
        return jdbcTemplate.query(
                "select * from sessions where application_id = ? and status <> ? order by updated_at desc",
                mapper,
                applicationId,
                SessionRecord.STATUS_DELETED);
    }

    @Override
    public void touch(String sessionId) {
        jdbcTemplate.update(
                "update sessions set updated_at = ? where application_id = ? and session_id = ?",
                JdbcTimeCodec.encode(Instant.now()),
                applicationId,
                sessionId);
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        jdbcTemplate.update(
                "update sessions set title = ?, updated_at = ? where application_id = ? and session_id = ?",
                title,
                JdbcTimeCodec.encode(Instant.now()),
                applicationId,
                sessionId);
    }

    @Override
    public void delete(String sessionId) {
        updateStatus(sessionId, SessionRecord.STATUS_DELETED);
    }

    public void updateStatus(String sessionId, String status) {
        jdbcTemplate.update(
                "update sessions set status = ?, updated_at = ? where application_id = ? and session_id = ?",
                status,
                JdbcTimeCodec.encode(Instant.now()),
                applicationId,
                sessionId);
    }

}

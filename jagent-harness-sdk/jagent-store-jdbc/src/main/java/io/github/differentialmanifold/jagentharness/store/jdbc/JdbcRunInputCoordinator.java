package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.github.differentialmanifold.jagentharness.core.agent.RunInput;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputCoordinator;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputReceipt;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JDBC-backed best-effort input queue for an agent run.
 *
 * <p>Pending inputs stay in {@code agent_run_inputs} until the agent loop checks the next safe
 * turn boundary. Queue operations intentionally do not open a transaction or seal the run at an
 * empty boundary. A late submission may therefore be acknowledged without being stored or
 * consumed, matching the queue's best-effort settling semantics.</p>
 */
public class JdbcRunInputCoordinator implements RunInputCoordinator {

    private static final String STATUS_ACCEPTED = RunInputStatus.ACCEPTED.name();
    private static final String STATUS_CLAIMED = RunInputStatus.CLAIMED.name();
    private static final String STATUS_CANCELLED = RunInputStatus.CANCELLED.name();

    private final JdbcTemplate jdbcTemplate;
    private final String applicationId;
    private final RowMapper<StoredInput> inputMapper = new RowMapper<StoredInput>() {
        @Override
        public StoredInput mapRow(ResultSet rs, int rowNum) throws SQLException {
            StoredInput stored = new StoredInput();
            stored.id = rs.getLong("id");
            stored.inputId = rs.getString("input_id");
            stored.sessionId = rs.getString("session_id");
            stored.runId = rs.getString("run_id");
            stored.content = rs.getString("content");
            stored.status = RunInputStatus.valueOf(rs.getString("status"));
            return stored;
        }
    };

    public JdbcRunInputCoordinator(JdbcTemplate jdbcTemplate, JdbcStoreProperties properties) {
        this.jdbcTemplate = requireJdbcTemplate(jdbcTemplate);
        this.applicationId = properties.requireApplicationId();
    }

    @Override
    public void activateRun(String sessionId, String runId) {
        requireText(sessionId, "sessionId");
        requireText(runId, "runId");

        ActiveRun active = findActiveRun(runId);
        if (active != null) {
            if (sessionId.equals(active.sessionId)) {
                return;
            }
            throw new IllegalStateException("Run is already active for another session: " + runId);
        }

        long now = System.currentTimeMillis();
        try {
            jdbcTemplate.update(
                    "insert into agent_active_runs "
                            + "(application_id, session_id, run_id, accepting_inputs, "
                            + "created_at, updated_at) values (?, ?, ?, 1, ?, ?)",
                    applicationId,
                    sessionId,
                    runId,
                    now,
                    now);
        } catch (DataAccessException e) {
            ActiveRun concurrent = findActiveRun(runId);
            if (concurrent != null && sessionId.equals(concurrent.sessionId)) {
                return;
            }
            throw e;
        }
    }

    @Override
    public RunInputReceipt submitInput(String runId, String content, String inputId) {
        requireText(runId, "runId");
        requireText(content, "content");
        requireText(inputId, "inputId");

        long now = System.currentTimeMillis();
        int inserted;
        try {
            inserted = jdbcTemplate.update(
                    "insert into agent_run_inputs "
                            + "(application_id, input_id, session_id, run_id, content, "
                            + "status, created_at, updated_at) "
                            + "select ?, ?, active.session_id, active.run_id, ?, ?, ?, ? "
                            + "from agent_active_runs active "
                            + "where active.application_id = ? and active.run_id = ? "
                            + "and active.accepting_inputs = 1",
                    applicationId,
                    inputId,
                    content,
                    STATUS_ACCEPTED,
                    now,
                    now,
                    applicationId,
                    runId);
        } catch (DataAccessException e) {
            StoredInput concurrent = findByInputId(inputId);
            if (concurrent != null) {
                return receiptForIdempotentSubmit(concurrent, runId, content);
            }
            throw e;
        }

        if (inserted == 0) {
            StoredInput existing = findByInputId(inputId);
            if (existing != null) {
                return receiptForIdempotentSubmit(existing, runId, content);
            }
        }

        // INSERT ... SELECT may insert zero rows when the run has already settled. The input API
        // deliberately acknowledges that late submission as best-effort instead of rejecting it.
        return new RunInputReceipt(inputId, RunInputStatus.ACCEPTED);
    }

    @Override
    public List<RunInput> claimPendingInputs(String sessionId,
                                             String runId,
                                             String completedTurnId) {
        requireText(sessionId, "sessionId");
        requireText(runId, "runId");
        requireText(completedTurnId, "completedTurnId");

        List<StoredInput> pending = jdbcTemplate.query(
                "select * from agent_run_inputs "
                        + "where application_id = ? and session_id = ? and run_id = ? "
                        + "and status = ? order by id asc",
                inputMapper,
                applicationId,
                sessionId,
                runId,
                STATUS_ACCEPTED);
        if (pending.isEmpty()) {
            return Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        List<RunInput> claimedInputs = new ArrayList<RunInput>(pending.size());
        for (StoredInput input : pending) {
            int claimed = claimPendingInput(input, completedTurnId, now);
            if (claimed == 1) {
                input.status = RunInputStatus.CLAIMED;
                claimedInputs.add(input.toRunInput());
            }
        }
        return claimedInputs.isEmpty() ? Collections.<RunInput>emptyList() : claimedInputs;
    }

    private int claimPendingInput(StoredInput input,
                                  String completedTurnId,
                                  long now) {
        return jdbcTemplate.update(
                "update agent_run_inputs set status = ?, claimed_after_turn_id = ?, "
                        + "claimed_at = ?, updated_at = ? "
                        + "where application_id = ? and id = ? and status = ?",
                STATUS_CLAIMED,
                completedTurnId,
                now,
                now,
                applicationId,
                input.id,
                STATUS_ACCEPTED);
    }

    @Override
    public void closeRun(String sessionId, String runId) {
        requireText(sessionId, "sessionId");
        requireText(runId, "runId");
        long now = System.currentTimeMillis();

        jdbcTemplate.update(
                "update agent_active_runs set accepting_inputs = 0, updated_at = ? "
                        + "where application_id = ? and session_id = ? and run_id = ?",
                now,
                applicationId,
                sessionId,
                runId);
        jdbcTemplate.update(
                "update agent_run_inputs set status = ?, updated_at = ? "
                        + "where application_id = ? and session_id = ? and run_id = ? "
                        + "and status = ?",
                STATUS_CANCELLED,
                now,
                applicationId,
                sessionId,
                runId,
                STATUS_ACCEPTED);
        jdbcTemplate.update(
                "delete from agent_active_runs "
                        + "where application_id = ? and session_id = ? and run_id = ?",
                applicationId,
                sessionId,
                runId);
    }

    private StoredInput findByInputId(String inputId) {
        List<StoredInput> rows = jdbcTemplate.query(
                "select * from agent_run_inputs where application_id = ? and input_id = ?",
                inputMapper,
                applicationId,
                inputId);
        return first(rows);
    }

    private ActiveRun findActiveRun(String runId) {
        List<ActiveRun> rows = jdbcTemplate.query(
                "select session_id from agent_active_runs "
                        + "where application_id = ? and run_id = ?",
                (rs, rowNum) -> new ActiveRun(rs.getString("session_id")),
                applicationId,
                runId);
        return first(rows);
    }

    private RunInputReceipt receiptForIdempotentSubmit(StoredInput existing,
                                                        String expectedRunId,
                                                        String expectedContent) {
        if (!Objects.equals(expectedRunId, existing.runId)
                || !Objects.equals(expectedContent, existing.content)) {
            throw new IllegalArgumentException(
                    "inputId is already used with different input: " + existing.inputId);
        }
        return new RunInputReceipt(existing.inputId, existing.status);
    }

    private static <T> T first(List<T> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private static JdbcTemplate requireJdbcTemplate(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate must not be null");
        }
        return jdbcTemplate;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static class ActiveRun {
        private final String sessionId;

        private ActiveRun(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    private static class StoredInput {
        private long id;
        private String inputId;
        private String sessionId;
        private String runId;
        private String content;
        private RunInputStatus status;

        private RunInput toRunInput() {
            return new RunInput(inputId, sessionId, runId, content, status);
        }
    }
}

package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.RunInput;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputCoordinator;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputReceipt;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputStatus;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
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
    private final ObjectMapper objectMapper;
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
            stored.images = readImages(rs.getString("images_json"));
            stored.status = RunInputStatus.valueOf(rs.getString("status"));
            return stored;
        }
    };

    public JdbcRunInputCoordinator(JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper,
                                   JdbcStoreProperties properties) {
        this.jdbcTemplate = requireJdbcTemplate(jdbcTemplate);
        this.objectMapper = requireObjectMapper(objectMapper);
        this.applicationId = properties.requireApplicationId();
    }

    public JdbcRunInputCoordinator(JdbcTemplate jdbcTemplate,
                                   JdbcStoreProperties properties) {
        this(jdbcTemplate, new ObjectMapper(), properties);
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
        return submitInput(runId, content, Collections.<MessageImage>emptyList(), inputId);
    }

    @Override
    public RunInputReceipt submitInput(String runId,
                                       String content,
                                       List<MessageImage> images,
                                       String inputId) {
        requireText(runId, "runId");
        requireText(inputId, "inputId");
        String normalizedContent = requireInputContent(content, images);
        List<MessageImage> normalizedImages = images == null
                ? Collections.<MessageImage>emptyList()
                : new ArrayList<MessageImage>(images);
        String imagesJson = writeImages(normalizedImages);

        long now = System.currentTimeMillis();
        int inserted;
        try {
            inserted = jdbcTemplate.update(
                    "insert into agent_run_inputs "
                            + "(application_id, input_id, session_id, run_id, content, images_json, "
                            + "status, created_at, updated_at) "
                            + "select ?, ?, active.session_id, active.run_id, ?, ?, ?, ?, ? "
                            + "from agent_active_runs active "
                            + "where active.application_id = ? and active.run_id = ? "
                            + "and active.accepting_inputs = 1",
                    applicationId,
                    inputId,
                    normalizedContent,
                    imagesJson,
                    STATUS_ACCEPTED,
                    now,
                    now,
                    applicationId,
                    runId);
        } catch (DataAccessException e) {
            StoredInput concurrent = findByInputId(inputId);
            if (concurrent != null) {
                return receiptForIdempotentSubmit(concurrent, runId, normalizedContent, normalizedImages);
            }
            throw e;
        }

        if (inserted == 0) {
            StoredInput existing = findByInputId(inputId);
            if (existing != null) {
                return receiptForIdempotentSubmit(existing, runId, normalizedContent, normalizedImages);
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
                                                        String expectedContent,
                                                        List<MessageImage> expectedImages) {
        if (!Objects.equals(expectedRunId, existing.runId)
                || !Objects.equals(expectedContent, existing.content)
                || !sameImages(expectedImages, existing.images)) {
            throw new IllegalArgumentException(
                    "inputId is already used with different input: " + existing.inputId);
        }
        return new RunInputReceipt(existing.inputId, existing.status);
    }

    private boolean sameImages(List<MessageImage> left, List<MessageImage> right) {
        List<MessageImage> normalizedLeft = left == null
                ? Collections.<MessageImage>emptyList()
                : left;
        List<MessageImage> normalizedRight = right == null
                ? Collections.<MessageImage>emptyList()
                : right;
        if (normalizedLeft.size() != normalizedRight.size()) {
            return false;
        }
        for (int index = 0; index < normalizedLeft.size(); index++) {
            MessageImage leftImage = normalizedLeft.get(index);
            MessageImage rightImage = normalizedRight.get(index);
            if (leftImage == null || rightImage == null) {
                if (leftImage != rightImage) {
                    return false;
                }
                continue;
            }
            if (!Objects.equals(leftImage.getName(), rightImage.getName())
                    || !Objects.equals(leftImage.getMediaType(), rightImage.getMediaType())
                    || !Objects.equals(leftImage.getUrl(), rightImage.getUrl())
                    || !Objects.equals(leftImage.getDetail(), rightImage.getDetail())) {
                return false;
            }
        }
        return true;
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

    private static ObjectMapper requireObjectMapper(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        return objectMapper;
    }

    private String writeImages(List<MessageImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(images);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize run input images", e);
        }
    }

    private List<MessageImage> readImages(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<MessageImage>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize run input images", e);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireInputContent(String content, List<MessageImage> images) {
        String value = content == null ? "" : content;
        if (value.trim().isEmpty() && (images == null || images.isEmpty())) {
            throw new IllegalArgumentException("content or images must not be empty");
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
        private List<MessageImage> images;
        private RunInputStatus status;

        private RunInput toRunInput() {
            RunInput input = new RunInput(inputId, sessionId, runId, content, status);
            input.setImages(images);
            return input;
        }
    }
}

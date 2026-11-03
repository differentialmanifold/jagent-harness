package io.github.differentialmanifold.jagentharness.store.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.skill.SkillActivation;
import io.github.differentialmanifold.jagentharness.core.skill.SkillActivationStore;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** One atomic insert per complete skill dependency set, with application and session isolation. */
public class JdbcSkillActivationStore implements SkillActivationStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String applicationId;

    public JdbcSkillActivationStore(
            JdbcTemplate db, ObjectMapper json, JdbcStoreProperties properties) {
        this.db = db;
        this.json = json;
        this.applicationId = properties.requireApplicationId();
    }

    @Override
    public List<SkillActivation> find(String sessionId, String scopeKey) {
        return db.query(
                "select activation_json from skill_activations where application_id = ? and session_id = ? and scope_key = ? order by activation_id",
                (rs, row) -> {
                    try {
                        return json.readValue(
                                rs.getString("activation_json"), SkillActivation.class);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Invalid persisted skill activation", e);
                    }
                },
                applicationId,
                sessionId,
                scopeKey);
    }

    @Override
    public void activate(String sessionId, String scopeKey, SkillActivation activation) {
        String serialized;
        try {
            serialized = json.writeValueAsString(activation);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize skill activation", e);
        }
        // Avoid duplicate writes in the ordinary reload path. The unique key also handles races.
        if (exists(sessionId, scopeKey, activation.activationId())) return;
        try {
            db.update(
                    "insert into skill_activations (application_id, session_id, scope_key, activation_id, activation_json) values (?, ?, ?, ?, ?)",
                    applicationId,
                    sessionId,
                    scopeKey,
                    activation.activationId(),
                    serialized);
        } catch (DataAccessException failure) {
            // SQLite reports duplicate primary keys as UncategorizedSQLException, unlike H2/PG.
            // Only accept the race when the complete immutable receipt really exists.
            if (!exists(sessionId, scopeKey, activation.activationId())) throw failure;
        }
    }

    private boolean exists(String sessionId, String scopeKey, String activationId) {
        Integer count =
                db.queryForObject(
                        "select count(*) from skill_activations where application_id = ? and session_id = ? and scope_key = ? and activation_id = ?",
                        Integer.class,
                        applicationId,
                        sessionId,
                        scopeKey,
                        activationId);
        return count != null && count > 0;
    }
}

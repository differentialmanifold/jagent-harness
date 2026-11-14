package io.github.differentialmanifold.jagentharness.store.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.model.ModelCatalogStore;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcModelCatalogStore implements ModelCatalogStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String app;

    public JdbcModelCatalogStore(
            JdbcTemplate db, ObjectMapper json, JdbcStoreProperties properties) {
        this.db = db;
        this.json = json;
        this.app = properties.requireApplicationId();
    }

    public Configuration configuration() {
        List<Configuration> rows =
                db.query(
                        "select payload_json, revision from model_settings where application_id=?",
                        (rs, n) -> {
                            Configuration c = read(rs.getString(1), Configuration.class);
                            c.revision = rs.getLong(2);
                            return c;
                        },
                        app);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean save(Configuration c) {
        try {
            return JdbcTransactions.write(db)
                    .execute(
                            tx -> {
                                if (c.revision == 0) {
                                    if (configuration() != null) return false;
                                    db.update(
                                            "insert into model_settings (application_id, revision, payload_json) values (?, 1, ?)",
                                            app,
                                            write(c));
                                    return true;
                                }
                                return db.update(
                                                "update model_settings set revision=revision+1, payload_json=? where application_id=? and revision=?",
                                                write(c),
                                                app,
                                                c.revision)
                                        == 1;
                            });
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            return false;
        }
    }

    public Selection selection(String session, String run) {
        List<Selection> rows =
                db.query(
                        "select payload_json from run_models where application_id=? and session_id=? and run_id=?",
                        (rs, n) -> read(rs.getString(1), Selection.class),
                        app,
                        session,
                        run);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void select(String session, String run, Selection selection) {
        JdbcTransactions.write(db)
                .execute(
                        tx -> {
                            if (db.update(
                                            "update run_models set payload_json=? where application_id=? and session_id=? and run_id=?",
                                            write(selection),
                                            app,
                                            session,
                                            run)
                                    == 0)
                                db.update(
                                        "insert into run_models (application_id, session_id, run_id, payload_json) values (?, ?, ?, ?)",
                                        app,
                                        session,
                                        run,
                                        write(selection));
                            return null;
                        });
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid model settings", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid stored model settings", e);
        }
    }
}

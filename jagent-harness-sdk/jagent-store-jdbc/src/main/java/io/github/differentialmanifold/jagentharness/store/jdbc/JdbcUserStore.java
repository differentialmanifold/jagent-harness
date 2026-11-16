package io.github.differentialmanifold.jagentharness.store.jdbc;

import io.github.differentialmanifold.jagentharness.core.identity.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Identity records use the deployment namespace; user data uses UserContext.namespace. */
public final class JdbcUserStore implements UserStore {
    private final JdbcTemplate db;
    private final String application;

    public JdbcUserStore(JdbcTemplate db, JdbcStoreProperties properties) {
        this.db = db;
        this.application = properties.requireApplicationId();
    }

    public User create(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 128)
            throw new IllegalArgumentException("A name of 1–128 characters is required");
        User user = new User();
        user.userId = "usr_" + UUID.randomUUID().toString().replace("-", "");
        user.name = name.trim();
        user.active = true;
        user.createdAt = System.currentTimeMillis();
        db.update(
                "insert into harness_users(application_id,user_id,name,token_hash,active,created_at) values(?,?,?,?,?,?)",
                application,
                user.userId,
                user.name,
                Credentials.hash(Credentials.token("unused_")),
                1,
                user.createdAt);
        return user;
    }

    public String initializeLocal() {
        if (get(UserContext.LOCAL) != null) return null;
        String token = Credentials.token("jhu_");
        try {
            db.update(
                    "insert into harness_users(application_id,user_id,name,token_hash,active,created_at) values(?,?,?,?,?,?)",
                    application,
                    UserContext.LOCAL,
                    "Local user",
                    Credentials.hash(token),
                    1,
                    System.currentTimeMillis());
            return token;
        } catch (org.springframework.dao.DuplicateKeyException concurrent) {
            if (get(UserContext.LOCAL) == null) throw concurrent;
            return null;
        }
    }

    private User row(java.sql.ResultSet r) throws java.sql.SQLException {
        User u = new User();
        u.userId = r.getString("user_id");
        u.name = r.getString("name");
        u.active = r.getInt("active") == 1;
        u.createdAt = r.getLong("created_at");
        return u;
    }

    public User get(String id) {
        return first(
                db.query(
                        "select * from harness_users where application_id=? and user_id=?",
                        (r, n) -> row(r),
                        application,
                        id));
    }

    public List<User> list() {
        return db.query(
                "select * from harness_users where application_id=? order by created_at,user_id",
                (r, n) -> row(r),
                application);
    }

    public List<String> userIds() {
        List<String> ids = new ArrayList<>();
        ids.add(UserContext.LOCAL);
        for (User u : list()) if (u.active && !ids.contains(u.userId)) ids.add(u.userId);
        return ids;
    }

    public String issueToken(String id) {
        String token = Credentials.token("jhu_");
        if (db.update(
                        "update harness_users set token_hash=?,active=1 where application_id=? and user_id=?",
                        Credentials.hash(token),
                        application,
                        id)
                != 1) throw new IllegalArgumentException("User not found");
        return token;
    }

    public User authenticate(String token) {
        if (token == null || token.length() > 256) return null;
        return first(
                db.query(
                        "select * from harness_users where application_id=? and token_hash=? and active=1",
                        (r, n) -> row(r),
                        application,
                        Credentials.hash(token)));
    }

    public void revoke(String id) {
        if (db.update(
                        "update harness_users set active=0 where application_id=? and user_id=?",
                        application,
                        id)
                != 1) throw new IllegalArgumentException("User not found");
    }

    public void saveAccess(Access a) {
        db.update(
                "delete from harness_access where application_id=? and expires_at<?",
                application,
                System.currentTimeMillis());
        db.update(
                "insert into harness_access(application_id,secret_hash,kind,user_id,runner_id,credential_hash,expires_at) values(?,?,?,?,?,?,?)",
                application,
                Credentials.hash(a.secret),
                a.kind,
                a.userId,
                a.runnerId,
                a.credentialHash,
                a.expiresAt);
    }

    public Access findAccess(String secret, String kind) {
        if (secret == null || secret.length() > 256) return null;
        Access a =
                first(
                        db.query(
                                "select a.* from harness_access a join harness_users u on a.application_id=u.application_id and a.user_id=u.user_id where a.application_id=? and a.secret_hash=? and a.kind=? and a.expires_at>? and u.active=1 and a.credential_hash=u.token_hash",
                                (r, n) -> {
                                    Access v = new Access();
                                    v.secret = secret;
                                    v.kind = r.getString("kind");
                                    v.userId = r.getString("user_id");
                                    v.runnerId = r.getString("runner_id");
                                    v.credentialHash = r.getString("credential_hash");
                                    v.expiresAt = r.getLong("expires_at");
                                    return v;
                                },
                                application,
                                Credentials.hash(secret),
                                kind,
                                System.currentTimeMillis()));
        return a;
    }

    public Access consumeTicket(String secret) {
        Access a = findAccess(secret, "ticket");
        if (a == null
                || db.update(
                                "delete from harness_access where application_id=? and secret_hash=? and kind='ticket'",
                                application,
                                Credentials.hash(secret))
                        != 1) return null;
        return a;
    }

    public boolean valid(Access a) {
        return a != null && findAccess(a.secret, a.kind) != null;
    }

    private static <T> T first(List<T> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }
}

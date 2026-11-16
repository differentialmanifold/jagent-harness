package io.github.differentialmanifold.jagentharness.core.identity;

import java.util.List;

/** Server-managed personal identities. Tokens never determine the storage namespace. */
public interface UserStore extends UserDirectory {
    User create(String name);

    String initializeLocal();

    User get(String id);

    List<User> list();

    String issueToken(String id);

    User authenticate(String token);

    void revoke(String id);

    void saveAccess(Access access);

    Access findAccess(String secret, String kind);

    Access consumeTicket(String secret);

    boolean valid(Access access);

    final class User {
        public String userId, name;
        public boolean active;
        public long createdAt;
    }

    final class Access {
        public String secret, kind, userId, runnerId, credentialHash;
        public long expiresAt;
    }
}

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

    /** Null credentialHash is reserved for local operator commands. */
    Access createTicket(String userId, String credentialHash);

    List<Device> devices(String userId);

    void revokeDevice(String userId, String deviceId);

    boolean startPair(String code, String claim, String browser, long expiresAt);

    String pairingBrowser(String code);

    boolean pendingPair(String claim);

    boolean approvePair(String code, String userId, String credentialHash);

    final class User {
        public String userId, name;
        public boolean active;
        public long createdAt;
    }

    final class Access {
        public String secret, kind, userId, runnerId, credentialHash;
        public long expiresAt;
    }

    /** Public browser-session metadata. Never expose a usable session secret. */
    final class Device {
        public String id;
        public long expiresAt;
        public boolean current;
    }
}

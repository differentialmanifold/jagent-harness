package io.github.differentialmanifold.jagentharness.core.identity;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

public final class Credentials {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Credentials() {}

    public static String token(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String secret) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(secret.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

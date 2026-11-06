package io.github.differentialmanifold.jagentharness.core.personal;

import java.nio.charset.StandardCharsets;
import java.security.*;

/** Shared validation and stable identifiers for Agent operations and channel adapters. */
public final class PersonalAgentSupport {
    private PersonalAgentSupport() {}

    public static String text(String value, String label, int max) {
        if (value == null || value.trim().isEmpty() || value.length() > max)
            throw new IllegalArgumentException(label + " must contain 1 to " + max + " characters");
        return value.trim();
    }

    public static String bounded(String text, int max) {
        return text == null ? "" : text.substring(0, Math.min(max, text.length()));
    }

    public static String hash(String value) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static PersonalAgentException conflict() {
        return new PersonalAgentException(
                PersonalAgentException.Kind.CONFLICT,
                "EDIT_CONFLICT",
                "This record changed. Reload it before saving.");
    }
}

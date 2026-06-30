package io.github.differentialmanifold.jagentharness.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class McpToolNames {

    private static final int MAX_LENGTH = 64;

    private McpToolNames() {
    }

    public static String modelName(String serverName, String toolName) {
        String raw = sanitize(serverName) + "__" + sanitize(toolName);
        if (raw.length() <= MAX_LENGTH) {
            return raw;
        }
        String hash = sha256(raw).substring(0, 8);
        return raw.substring(0, MAX_LENGTH - hash.length() - 1) + "_" + hash;
    }

    private static String sanitize(String value) {
        String input = value == null ? "" : value.trim();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '_'
                    || current == '-') {
                result.append(current);
            } else {
                result.append('_');
            }
        }
        return result.length() == 0 ? "mcp" : result.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte current : bytes) {
                result.append(String.format("%02x", current & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }
}

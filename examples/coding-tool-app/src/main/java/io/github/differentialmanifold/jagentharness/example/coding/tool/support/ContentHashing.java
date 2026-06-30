package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ContentHashing {

    private ContentHashing() {
    }

    public static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available.", e);
        }
    }

    public static String sha256(byte[] bytes) {
        return toHex(newSha256Digest().digest(bytes));
    }

    public static String toHex(byte[] digest) {
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            value.append(String.format("%02x", b & 0xff));
        }
        return value.toString();
    }
}

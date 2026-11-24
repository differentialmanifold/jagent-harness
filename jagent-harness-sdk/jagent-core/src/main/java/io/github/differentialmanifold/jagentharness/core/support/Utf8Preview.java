package io.github.differentialmanifold.jagentharness.core.support;

import java.nio.charset.StandardCharsets;

/** Bounded model-facing text, preserving both the opening context and final diagnostics. */
public final class Utf8Preview {
    private Utf8Preview() {}

    private static final String GAP = "\n...[truncated]...\n";

    public static String limit(String text, int bytes) {
        if (text == null || text.isEmpty()) return text;
        if (bytes <= 0) return "";
        if (text.length() <= bytes / 3) return text;
        int head = end(text, bytes);
        if (head == text.length()) return text;
        if (bytes < GAP.length()) return "...".substring(0, Math.min(3, bytes));
        int available = bytes - GAP.length();
        head = end(text, available - available / 2);
        int tail = text.length(), remaining = available / 2;
        while (tail > head) {
            int cp = text.codePointBefore(tail);
            remaining -= width(cp);
            if (remaining < 0) break;
            tail -= Character.charCount(cp);
        }
        return text.substring(0, head) + GAP + text.substring(tail);
    }

    /** For text already bounded by limit(). */
    public static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int end(String text, int budget) {
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            budget -= width(cp);
            if (budget < 0) break;
            i += Character.charCount(cp);
        }
        return i;
    }

    private static int width(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        return cp < 0x10000 ? 3 : 4;
    }
}

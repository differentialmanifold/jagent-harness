package io.github.differentialmanifold.jagentharness.core.personal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Final-output contract. Delivery is program-owned; tool activity remains in web history. */
final class PersonalAgentReply {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String INSTRUCTIONS =
            "After all tool calls, return your user-facing reply as ordinary text or Markdown. "
                    + "Direct user or [AGENT] messages require a non-empty reply. Replies go to the originating sender, which may be another Agent. "
                    + "For a background [PROJECT], [CHECK], [CONTINUE], [AGENT_REPLY] or [RECOVERY] event, report only meaningful progress, completion, failure, or a question requiring user input. "
                    + "If nothing needs the user's attention, use {\"userReply\":null}. Still perform needed work and schedule the next check before finishing. "
                    + "Use the null object only as an explicit silent-completion signal, without a code fence. "
                    + "The program stores this reply in the web chat and delivers it to the original external channel, if any. No notification tool is needed.\n";

    static String decode(String raw, boolean background) {
        if (raw == null || raw.trim().isEmpty())
            throw new IllegalArgumentException("The model returned an empty final response");
        String candidate = raw.trim();
        // Providers do not all support enforced structured output. Preserve ordinary
        // replies, including code blocks; unwrap only a recognized reply envelope.
        if (candidate.startsWith("```json\n") || candidate.startsWith("```\n")) {
            int newline = candidate.indexOf('\n');
            if (candidate.endsWith("```"))
                candidate = candidate.substring(newline + 1, candidate.length() - 3).trim();
        }
        JsonNode node = null;
        try {
            node =
                    JSON.reader()
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(candidate);
        } catch (java.io.IOException ignored) {
            // Plain prose, Markdown and incomplete JSON are content, not control signals.
        }
        if (node != null && node.isObject() && node.has("userReply")) {
            JsonNode reply = node.get("userReply");
            if (reply.isNull() && background) return "";
            if (reply.isTextual() && !reply.asText().trim().isEmpty()) return reply.asText();
            throw new IllegalArgumentException(
                    "The model returned a reply envelope without a non-empty userReply; null is only allowed for background events");
        }
        return raw;
    }
}

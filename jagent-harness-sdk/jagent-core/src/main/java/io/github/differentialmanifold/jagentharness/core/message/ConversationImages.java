package io.github.differentialmanifold.jagentharness.core.message;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.session.SessionStore;
import java.util.*;

/**
 * A reference is a stored message ID and one-based image index, resolved only in the caller's
 * session.
 */
public final class ConversationImages {
    private ConversationImages() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String REPLY_FORMAT =
            "\nTo deliver selected images in your final reply, return a JSON object with userReply (caption or answer) and attachmentIds (IDs listed on messages in this conversation). Otherwise reply normally. Reading an image does not automatically publish it. Never put image data or local paths in place of attachments.";

    /** Run-local selection for tools explicitly attaching files to the next user-facing reply. */
    public static final class ReplySelection {
        public static final String ATTRIBUTE = "replyImageSelection";
        private List<MessageImage> images = Collections.emptyList();

        public void select(List<MessageImage> images) {
            if (images.isEmpty())
                throw new IllegalArgumentException("Select at least one image to attach");
            // resolve() has already validated count, size, format and ownership at the tool
            // boundary.
            this.images = new ArrayList<>(images);
        }

        public void apply(AgentMessage reply) {
            if (reply.getContent() != null && !reply.getContent().trim().isEmpty() && !images.isEmpty())
                reply.setImages(images);
        }
    }

    public static List<String> ids(AgentMessage m) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < m.getImages().size(); i++) ids.add(m.getMessageId() + ":" + (i + 1));
        return ids;
    }

    public static boolean hasImages(List<AgentMessage> history) {
        return history.stream().anyMatch(m -> !m.getImages().isEmpty());
    }

    /** Add reference labels to model input without modifying the stored transcript. */
    public static List<AgentMessage> label(List<AgentMessage> history) {
        List<AgentMessage> result = new ArrayList<>(history.size());
        for (AgentMessage m : history) {
            if (m.getImages().isEmpty()) {
                result.add(m);
                continue;
            }
            AgentMessage copy = m.copy();
            StringBuilder text = new StringBuilder(Objects.toString(m.getContent(), ""));
            List<String> ids = ids(m);
            for (int i = 0; i < ids.size(); i++)
                text.append("\nImage attachment ")
                        .append(ids.get(i))
                        .append(": ")
                        .append(m.getImages().get(i).getName());
            copy.setContent(text.toString());
            result.add(copy);
        }
        return result;
    }

    public static List<String> readIds(JsonNode value) {
        if (value == null) return Collections.emptyList();
        if (!value.isArray() || value.size() > ImageInputValidator.MAX_IMAGES)
            throw new IllegalArgumentException(
                    "attachmentIds must be a list of at most four image references");
        List<String> ids = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()
                    || !item.asText().matches("[A-Za-z0-9_-]{1,128}:[1-4]")
                    || ids.contains(item.asText()))
                throw new IllegalArgumentException(
                        "Expected distinct image references from this conversation");
            ids.add(item.asText());
        }
        return ids;
    }

    public static List<MessageImage> resolve(SessionStore store, String session, List<String> ids) {
        return resolve(store, session, ids, message -> true);
    }

    public static List<MessageImage> resolve(
            SessionStore store,
            String session,
            List<String> ids,
            java.util.function.Predicate<AgentMessage> allowed) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        // Validate persisted receipts and tool input alike.
        readIds(JSON.valueToTree(ids));
        Map<String, AgentMessage> messages = new HashMap<>();
        List<MessageImage> images = new ArrayList<>();
        for (String id : ids) {
            int colon = id.indexOf(':');
            String key = id.substring(0, colon);
            AgentMessage message =
                    messages.computeIfAbsent(key, k -> store.findMessage(session, k));
            int i = Integer.parseInt(id.substring(colon + 1)) - 1;
            if (message == null || !allowed.test(message) || i >= message.getImages().size())
                throw new IllegalArgumentException(
                        "Image attachment is unavailable in this conversation: " + id);
            images.add(message.getImages().get(i));
        }
        return ImageInputValidator.normalize(images);
    }

    /** Keep JSON envelopes out of live chat while streaming normal prose unchanged. */
    public static final class ReplyStream {
        private StringBuilder prefix = new StringBuilder();
        private boolean structured;

        public String accept(String delta) {
            if (structured) return null;
            if (prefix == null) return delta;
            prefix.append(delta);
            String start = prefix.toString().trim();
            if (start.isEmpty() || "```".startsWith(start)) return null;
            structured = start.startsWith("{") || start.startsWith("```");
            String result = structured ? null : prefix.toString();
            prefix = null;
            return result;
        }

        public void reset() {
            prefix = new StringBuilder();
            structured = false;
        }
    }

    /**
     * Recognize only the attachment reply envelope; plain Markdown and silent completions are
     * untouched.
     */
    public static void applyReply(AgentMessage reply, SessionStore store) {
        String raw = Objects.toString(reply.getContent(), "").trim();
        if ((raw.startsWith("```json\n") || raw.startsWith("```\n")) && raw.endsWith("```"))
            raw = raw.substring(raw.indexOf('\n') + 1, raw.length() - 3).trim();
        if (!raw.startsWith("{")) return;
        JsonNode node;
        try {
            node = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
        } catch (java.io.IOException ignored) {
            return;
        }
        if (!node.has("userReply") || !node.has("attachmentIds")) return;
        if (!node.path("userReply").isTextual() || node.path("userReply").asText().trim().isEmpty())
            throw new IllegalArgumentException("An image reply requires a caption or answer");
        reply.setImages(resolve(store, reply.getSessionId(), readIds(node.get("attachmentIds"))));
        reply.setContent(node.get("userReply").asText());
    }
}

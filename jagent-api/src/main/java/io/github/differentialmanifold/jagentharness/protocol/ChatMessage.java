package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** JSON wire contract. Independent of any framework and JSON library. */
public class ChatMessage {
    public String role;
    public String toolCallId;
    public String status;
    public Object content;
    public List<Map<String, Object>> images = new ArrayList<>();

    public static ChatMessage user(String content) {
        ChatMessage m = new ChatMessage();
        m.role = "user";
        m.content = content;
        return m;
    }

    public static ChatMessage tool(String id, String status, Object content) {
        ChatMessage m = new ChatMessage();
        m.role = "tool";
        m.toolCallId = id;
        m.status = status;
        m.content = content;
        return m;
    }
}

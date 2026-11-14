package io.github.differentialmanifold.jagentharness.protocol;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.ArrayList;
import java.util.List;

/** Public user input. Execution tools and instructions come from the project configuration. */
public class ChatSubmission {
    public String modelId;
    public String sessionId;
    public String runId;
    public List<ChatMessage> messages = new ArrayList<>();
    public boolean stream;

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unsupported chat submission field: " + name);
    }
}

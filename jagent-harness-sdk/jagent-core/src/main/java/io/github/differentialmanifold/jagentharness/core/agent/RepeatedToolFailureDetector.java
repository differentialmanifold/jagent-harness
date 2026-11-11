package io.github.differentialmanifold.jagentharness.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.ToolOutcome;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import java.util.*;

/** Conservative, history-based detection; no counters are lost at a client-tool HTTP boundary. */
final class RepeatedToolFailureDetector {
    static final int WINDOW_SIZE = 64;
    static final String REMINDER =
            "The last three completed tool batches failed with identical tools, arguments and errors. "
                    + "Do not repeat the same calls unchanged. Inspect the failure, change your approach, "
                    + "or explain the blocker to the user. Another identical failed batch will stop this run.";

    enum Decision {
        CONTINUE,
        REMIND,
        STOP
    }

    private final ObjectMapper json;

    RepeatedToolFailureDetector(ObjectMapper json) {
        this.json = json;
    }

    Decision inspect(List<AgentMessage> history) {
        List<ToolCall> calls = Collections.emptyList();
        Map<String, AgentMessage> results = new HashMap<>();
        Map<List<Object>, Integer> previous = null;
        int repeats = 0;
        int remindedAt = -1;
        for (AgentMessage message : history) {
            if (AgentMessage.isRuntimeContext(message)) {
                if (REMINDER.equals(message.getContent()) && previous != null) remindedAt = repeats;
                continue;
            }
            if ("user".equals(message.getRole())) {
                calls = Collections.emptyList();
                results.clear();
                previous = null;
                repeats = 0;
                remindedAt = -1;
            } else if ("assistant".equals(message.getRole())) {
                calls =
                        message.getToolCalls() == null
                                ? Collections.emptyList()
                                : message.getToolCalls();
                results.clear();
                if (calls.isEmpty()) {
                    previous = null;
                    repeats = 0;
                    remindedAt = -1;
                }
            } else if ("tool".equals(message.getRole()) && !calls.isEmpty()) {
                results.put(message.getToolCallId(), message);
                if (!calls.stream().allMatch(call -> results.containsKey(call.getToolCallId())))
                    continue;
                Map<List<Object>, Integer> signature = signature(calls, results);
                if (signature != null && signature.equals(previous)) {
                    repeats++;
                } else {
                    repeats = signature == null ? 0 : 1;
                    remindedAt = -1;
                }
                previous = signature;
                calls = Collections.emptyList();
                results.clear();
            }
        }
        if (!calls.isEmpty()) return Decision.CONTINUE;
        if (remindedAt >= 0 && repeats > remindedAt) return Decision.STOP;
        if (repeats >= 3 && remindedAt < 0) return Decision.REMIND;
        return Decision.CONTINUE;
    }

    private Map<List<Object>, Integer> signature(
            List<ToolCall> calls, Map<String, AgentMessage> results) {
        Map<List<Object>, Integer> signature = new HashMap<>();
        for (ToolCall call : calls) {
            AgentMessage result = results.get(call.getToolCallId());
            // Success, unknown outcomes and older untyped results cannot prove a failed loop.
            if (!ToolOutcome.failed(result)) return null;
            List<Object> key =
                    Arrays.asList(
                            call.getName(),
                            call.isClientExecution(),
                            parsed(call.getArgumentsJson()),
                            parsed(result.getContent()));
            signature.put(key, signature.getOrDefault(key, 0) + 1);
        }
        return signature;
    }

    private Object parsed(String text) {
        if (text == null) return null;
        String content =
                text.startsWith("Tool execution FAILED: ")
                        ? text.substring("Tool execution FAILED: ".length())
                        : text;
        try {
            return json.reader()
                    .with(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_TRAILING_TOKENS)
                    .readTree(content);
        } catch (java.io.IOException ignored) {
            return content;
        }
    }
}

package io.github.differentialmanifold.jagentharness.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolJournalTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void completedEffectsAreNotReplayedAndChangedArgumentsAreRejected() throws Exception {
        AtomicInteger effects = new AtomicInteger();
        ToolJournal journal = new ToolJournal(root);
        ToolDefinition wrapped = journal.wrap(tool(effects));
        ToolContext context =
                new ToolContext("session", "run", "turn").forToolCall("call", "write");
        JsonNode args = json.createObjectNode().put("text", "one");
        assertEquals("SUCCEEDED", wrapped.execute(context, args).status);
        assertEquals("SUCCEEDED", journal.wrap(tool(effects)).execute(context, args).status);
        assertEquals(1, effects.get());
        assertThrows(
                IllegalArgumentException.class,
                () -> wrapped.execute(context, json.createObjectNode().put("text", "two")));
    }

    @Test
    void crashAfterStartingIsUnknownAndDoesNotExecuteAgain() throws Exception {
        AtomicInteger effects = new AtomicInteger();
        ToolJournal journal = new ToolJournal(root);
        JsonNode args = json.createObjectNode();
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("tool", "write");
        started.put("arguments", args);
        started.put("state", "STARTED");
        RunnerFiles.save(journal.path("s", "c"), started);
        ToolExecutionResult value =
                journal.wrap(tool(effects))
                        .execute(new ToolContext("s", "r", "t").forToolCall("c", "write"), args);
        assertEquals("UNKNOWN", value.status);
        assertEquals(0, effects.get());
    }

    private ToolDefinition tool(AtomicInteger effects) {
        return new ToolDefinition() {
            public String getName() {
                return "write";
            }

            public String getDescription() {
                return "test";
            }

            public JsonNode getParametersSchema() {
                return json.createObjectNode().put("type", "object");
            }

            public ToolExecutionResult execute(ToolContext context, JsonNode args) {
                effects.incrementAndGet();
                return ToolExecutionResult.success("done");
            }
        };
    }
}

package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.sqlite.SQLiteDataSource;

class JdbcMessageWindowTest {
    @TempDir Path temp;

    @Test
    void readsOnlyNeededMessagesWhileRetainingFullHistoryInBothDatabases() {
        for (JdbcTemplate db : databases()) {
            JdbcStoreProperties properties = new JdbcStoreProperties();
            JdbcMessageRepository messages =
                    new JdbcMessageRepository(db, new ObjectMapper(), properties);
            AgentMessage cursor = null;
            for (int i = 0; i < 100; i++) {
                cursor = AgentMessage.user("s", "old-" + i);
                append(messages, cursor);
            }
            JdbcCompactionStore compaction = new JdbcCompactionStore(db, properties);
            compaction.save("s", "Summary", cursor.getMessageId());
            // Any accidental deserialization of compacted rows now fails this test.
            db.update(
                    "update messages set tool_calls_json = 'invalid-json' where session_id = 's'");
            ToolCall call = new ToolCall("pending", "client", "{}");
            call.setClientExecution(true);
            AgentMessage assistant =
                    AgentMessage.assistant("s", "", Collections.singletonList(call));
            assistant.setRunId("run");
            assistant.setTurnId("turn");
            append(messages, assistant);
            assertEquals(1, messages.findContextBySessionId("s").size());
            assertEquals(1, messages.findRecentBySessionId("s", 1).size());
            assertEquals(
                    1,
                    ConversationHistory.pendingTools(messages.findExecutionBySessionId("s"))
                            .size());
            AgentMessage result = AgentMessage.tool("s", "pending", "client", "failed");
            ToolOutcome.record(result, "FAILED");
            result.setRunId("run");
            result.setTurnId("turn");
            append(messages, result);
            assertTrue(
                    ConversationHistory.pendingTools(messages.findExecutionBySessionId("s"))
                            .isEmpty());
            assertTrue(ToolOutcome.failed(messages.findRecentBySessionId("s", 1).get(0)));
            assertEquals(
                    assistant.getMessageId(),
                    messages.findRecentBySessionId("s", 2).get(0).getMessageId());

            // A failure receipt must not hide the preceding call/results from protocol validation.
            append(
                    messages,
                    FailedTurn.receipt(
                            messages.findExecutionBySessionId("s"), "s", "run", "next", "blocked"));
            assertEquals(3, messages.findExecutionBySessionId("s").size());
            assertEquals(2, messages.findContextBySessionId("s").size());
            db.update(
                    "update messages set tool_calls_json = null where tool_calls_json = 'invalid-json'");
            assertEquals(103, messages.findBySessionId("s").size());

            compaction.save("s", "Invalid cursor", "missing");
            assertEquals(102, messages.findContextBySessionId("s").size());
            JdbcStoreProperties other = new JdbcStoreProperties();
            other.setApplicationId("other");
            new JdbcCompactionStore(db, other).save("s", "Other app", result.getMessageId());
            assertEquals(102, messages.findContextBySessionId("s").size());
        }
    }

    @Test
    void executionWindowDoesNotCutOffLargePendingBatches() {
        for (JdbcTemplate db : databases()) {
            JdbcMessageRepository messages =
                    new JdbcMessageRepository(db, new ObjectMapper(), new JdbcStoreProperties());
            List<ToolCall> calls = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                ToolCall call = new ToolCall("c" + i, "client", "{}");
                call.setClientExecution(true);
                calls.add(call);
            }
            append(messages, AgentMessage.assistant("s", "", calls));
            for (int i = 0; i < 99; i++)
                append(messages, AgentMessage.tool("s", "c" + i, "client", "ok"));
            Map<String, ToolCall> pending =
                    ConversationHistory.pendingTools(messages.findExecutionBySessionId("s"));
            assertEquals(Collections.singleton("c99"), pending.keySet());
        }
    }

    @Test
    void operationStateReadsTheLatestBoundaryWithoutLoadingDeltas() {
        for (JdbcTemplate db : databases()) {
            JdbcTimelineEventRepository timeline =
                    new JdbcTimelineEventRepository(db, new JdbcStoreProperties());
            assertNull(timeline.findLatestRunBoundary("s"));
            for (String type :
                    Arrays.asList(
                            "agent_end", "agent_start", "message_update", "tool_execution_end")) {
                AgentEvent event = new AgentEvent();
                event.setEventId(UUID.randomUUID().toString());
                event.setSessionId("s");
                event.setRunId("run");
                event.setType(type);
                event.setPayloadJson("{}");
                event.setCreatedAt(Instant.now());
                timeline.append(event);
            }
            assertEquals("agent_start", timeline.findLatestRunBoundary("s").getType());
            assertNull(timeline.findLatestRunBoundary("other"));
        }
    }

    private void append(JdbcMessageRepository messages, AgentMessage message) {
        if (message.getRunId() == null) message.setRunId("run");
        if (message.getTurnId() == null) message.setTurnId("turn");
        messages.append(message);
    }

    private List<JdbcTemplate> databases() {
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + temp.resolve(UUID.randomUUID() + ".db"));
        DataSource h2 =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        List<JdbcTemplate> result = new ArrayList<>();
        for (DataSource source : Arrays.asList(sqlite, h2)) {
            new JdbcSchemaInitializer(source).initialize();
            result.add(new JdbcTemplate(source));
        }
        return result;
    }
}

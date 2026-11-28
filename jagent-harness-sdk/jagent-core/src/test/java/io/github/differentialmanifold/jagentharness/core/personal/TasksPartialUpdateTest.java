package io.github.differentialmanifold.jagentharness.core.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.Conversation;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.Profile;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Partial updates of a task card: when the arguments carry an existing card {@code id}, the
 * action's {@code required} list applies to creation only. Revision enforcement and the
 * ownership check live in {@link TasksTool#run} and are deliberately out of scope here; this
 * test drives the real {@link PersonalTool#execute} → {@code validate} path with the same
 * {@code required} contract as the production {@code tasks save} action ("title", "status").
 */
class TasksPartialUpdateTest {

    private static final String AGENT = "agent-a";
    private static final String SESSION = "session-1";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Same shape as the production {@code tasks save} action; {@code run} only records args. */
    private static final class SaveOnlyTool extends PersonalTool {
        final AtomicReference<JsonNode> seen = new AtomicReference<>();

        SaveOnlyTool(PersonalAgentService services, PersonalAgentStore store) {
            super("tasks", "test", () -> services, store, JSON);
            ObjectNode fields = JSON.createObjectNode();
            // Same declared fields as the production tasks save action (TasksTool).
            fields.set("id", text("Existing task ID; omit to create."));
            fields.set("title", text("Title."));
            fields.set("status", text("Status."));
            fields.set("progress", text("Progress note."));
            fields.set("revision", number("Expected revision for updates."));
            action(
                    "save",
                    "Create or update a task; omitted fields are preserved",
                    fields,
                    new String[] {"cards_save"},
                    "title",
                    "status");
        }

        @Override
        Object run(String id, ToolContext context, String action, JsonNode args) {
            seen.set(args);
            return "saved";
        }
    }

    private static SaveOnlyTool tool() {
        PersonalAgentService services = mock(PersonalAgentService.class);
        when(services.require(AGENT)).thenReturn(new Profile());
        PersonalAgentStore store = mock(PersonalAgentStore.class);
        Conversation c = new Conversation();
        c.agentId = AGENT;
        when(store.conversation(SESSION)).thenReturn(c);
        return new SaveOnlyTool(services, store);
    }

    private static ToolContext ctx() {
        ToolContext context = mock(ToolContext.class);
        // PersonalTool.identity(store, ctx) reads these two and the store's conversation.
        when(context.getAttribute("personalAgentId", String.class)).thenReturn(AGENT);
        when(context.getSessionId()).thenReturn(SESSION);
        // PersonalTool.execute checks the stop signal after validate() and before run().
        when(context.getStopSignal()).thenReturn(StopSignal.none());
        return context;
    }

    @Test
    void updateWithOnlyIdRevisionAndProgressIsAccepted() throws Exception {
        SaveOnlyTool tool = tool();
        JsonNode args =
                JSON.readTree(
                        "{\"action\":\"save\",\"id\":\"card-1\",\"revision\":0,"
                                + "\"progress\":\"half done\"}");
        ToolExecutionResult out = tool.forProfile(new Profile()).execute(ctx(), args);
        // run() was reached (validate did not reject the partial update) with the args intact.
        assertNotNull(tool.seen.get(), "run() must be reached for a partial update");
        assertEquals("card-1", tool.seen.get().path("id").asText());
        assertEquals("half done", tool.seen.get().path("progress").asText());
        assertNotNull(out);
    }

    @Test
    void createWithoutTitleStillRequiresTitle() throws Exception {
        SaveOnlyTool tool = tool();
        JsonNode args = JSON.readTree("{\"action\":\"save\",\"status\":\"TODO\"}");
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> tool.forProfile(new Profile()).execute(ctx(), args));
        assertEquals("Missing required field: title", ex.getMessage());
    }
}
